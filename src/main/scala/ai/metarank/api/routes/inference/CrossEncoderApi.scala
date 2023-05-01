package ai.metarank.api.routes.inference

import ai.metarank.ml.onnx.sbert.{OnnxCrossEncoder, OnnxSession}
import ai.metarank.api.JsonChunk
import ai.metarank.api.routes.inference.BiEncoderApi.{BiencoderRequest, BiencoderResponse}
import ai.metarank.api.routes.inference.CrossEncoderApi.{CrossEncoderRequest, CrossEncoderResponse}
import ai.metarank.ml.onnx.encoder.EncoderConfig
import ai.metarank.ml.onnx.encoder.EncoderConfig.CrossEncoderConfig
import ai.metarank.ml.onnx.sbert.OnnxCrossEncoder.SentencePair
import ai.metarank.util.Logging
import cats.effect._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.http4s.circe._
import cats.implicits._

case class CrossEncoderApi(encoders: Map[String, OnnxCrossEncoder]) {
  val routes = HttpRoutes.of[IO] {
    { case post @ POST -> Root / "inference" / "cross" / model =>
      for {
        request <- post.as[CrossEncoderRequest]
        encoder <- IO.fromOption(encoders.get(model))(
          new Exception(s"cross-encoder $model is not defined in config (defined: ${encoders.keys.toList})")
        )
        scores   <- IO(encoder.encode(request.input.map(sp => SentencePair(sp.query, sp.text)).toArray))
        response <- IO(CrossEncoderResponse(scores))
      } yield {
        Response[IO](
          entity = Entity.strict(JsonChunk(response)),
          headers = Headers(`Content-Type`(MediaType.application.json))
        )
      }
    }
  }

}

object CrossEncoderApi extends Logging {
  case class QueryDocumentPair(query: String, text: String)
  case class CrossEncoderRequest(input: List[QueryDocumentPair])
  case class CrossEncoderResponse(scores: Array[Float])

  implicit val qdpCodec: Codec[QueryDocumentPair]              = deriveCodec[QueryDocumentPair]
  implicit val crossRequestCodec: Codec[CrossEncoderRequest]   = deriveCodec[CrossEncoderRequest]
  implicit val crossResponseCodec: Codec[CrossEncoderResponse] = deriveCodec[CrossEncoderResponse]

  implicit val crossRequestJson: EntityDecoder[IO, CrossEncoderRequest]   = jsonOf[IO, CrossEncoderRequest]
  implicit val crossResponseJson: EntityEncoder[IO, CrossEncoderResponse] = jsonEncoderOf[CrossEncoderResponse]

  def create(models: Map[String, EncoderConfig]): IO[CrossEncoderApi] = for {
    bi <- IO(models.collect { case (name, c: CrossEncoderConfig) =>
      name -> c
    })
    encoders <- bi.toList.traverseCollect { case (name, CrossEncoderConfig(Some(handle), _, mf, vc)) =>
      OnnxSession.load(handle, mf, vc).map(session => name -> OnnxCrossEncoder(session))
    }
    _ <- IO.whenA(encoders.nonEmpty)(info(s"loaded ${encoders.map(_._1).toList} cross-encoders for inference"))
  } yield {
    CrossEncoderApi(encoders.toMap)
  }

}
