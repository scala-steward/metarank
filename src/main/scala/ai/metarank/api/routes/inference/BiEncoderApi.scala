package ai.metarank.api.routes.inference

import ai.metarank.ml.onnx.sbert.{OnnxBiEncoder, OnnxSession}
import ai.metarank.api.JsonChunk
import ai.metarank.api.routes.inference.BiEncoderApi.{BiencoderRequest, BiencoderResponse}
import ai.metarank.api.routes.inference.CrossEncoderApi.info
import ai.metarank.ml.onnx.encoder.EncoderConfig
import ai.metarank.ml.onnx.encoder.EncoderConfig.BiEncoderConfig
import ai.metarank.util.Logging
import cats.effect._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.http4s.circe._
import cats.implicits._

case class BiEncoderApi(encoders: Map[String, OnnxBiEncoder]) {
  val routes = HttpRoutes.of[IO] {
    { case post @ POST -> Root / "inference" / "encoder" / model =>
      for {
        request <- post.as[BiencoderRequest]
        encoder <- IO.fromOption(encoders.get(model))(
          new Exception(s"encoder $model is not defined in config (defined: ${encoders.keys.toList})")
        )
        encoded  <- IO(encoder.embed(request.texts.toArray))
        response <- IO(BiencoderResponse(encoded.toList))
      } yield {
        Response[IO](
          entity = Entity.strict(JsonChunk(response)),
          headers = Headers(`Content-Type`(MediaType.application.json))
        )
      }
    }
  }
}

object BiEncoderApi extends Logging {
  case class BiencoderRequest(texts: List[String])
  case class BiencoderResponse(embeddings: List[Array[Float]])

  implicit val biRequestCodec: Codec[BiencoderRequest]   = deriveCodec
  implicit val biResponseCodec: Codec[BiencoderResponse] = deriveCodec

  implicit val biRequestJson: EntityDecoder[IO, BiencoderRequest]   = jsonOf[IO, BiencoderRequest]
  implicit val biResponseJson: EntityEncoder[IO, BiencoderResponse] = jsonEncoderOf[BiencoderResponse]

  def create(models: Map[String, EncoderConfig]): IO[BiEncoderApi] = for {
    bi <- IO(models.collect { case (name, c: BiEncoderConfig) =>
      name -> c
    })
    encoders <- bi.toList.traverseCollect { case (name, BiEncoderConfig(Some(handle), _, _, mf, vc, _)) =>
      OnnxSession.load(handle, mf, vc).map(session => name -> OnnxBiEncoder(session))
    }
    _ <- IO.whenA(encoders.nonEmpty)(info(s"loaded ${encoders.map(_._1).toList} bi-encoders for inference"))
  } yield {
    BiEncoderApi(encoders.toMap)
  }
}
