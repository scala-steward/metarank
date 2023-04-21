package ai.metarank.ml.onnx.sbert

import ai.djl.modality.nlp.DefaultVocabulary
import ai.djl.modality.nlp.bert.BertFullTokenizer
import ai.metarank.ml.onnx.HuggingFaceClient
import ai.metarank.ml.onnx.ModelHandle.{HuggingFaceHandle, LocalModelHandle}
import ai.metarank.util.{LocalCache, Logging}
import ai.onnxruntime.{OrtEnvironment, OrtSession, TensorInfo}
import ai.onnxruntime.OrtSession.SessionOptions
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import cats.effect.IO
import org.apache.commons.io.{FileUtils, IOUtils}

import java.io.{ByteArrayInputStream, File, FileInputStream, InputStream}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._

case class OnnxSession(env: OrtEnvironment, session: OrtSession, tokenizer: BertFullTokenizer, dim: Int)

object OnnxSession extends Logging {
  def load(model: InputStream, dic: InputStream): IO[OnnxSession] = IO {
    val tokens    = IOUtils.toString(dic, StandardCharsets.UTF_8).split('\n')
    val vocab     = DefaultVocabulary.builder().add(tokens.toList.asJava).build()
    val tokenizer = new BertFullTokenizer(vocab, true)
    val env       = OrtEnvironment.getEnvironment("sbert")
    val opts      = new SessionOptions()
    opts.setIntraOpNumThreads(Runtime.getRuntime.availableProcessors())
    opts.setOptimizationLevel(OptLevel.ALL_OPT)
    val modelBytes = IOUtils.toByteArray(model)
    val session    = env.createSession(modelBytes)
    val size       = FileUtils.byteCountToDisplaySize(modelBytes.length)
    val inputs     = session.getInputNames.asScala.toList
    val outputs    = session.getOutputNames.asScala.toList
    val dim = session.getOutputInfo.asScala
      .get("last_hidden_state")
      .flatMap(_.getInfo.asInstanceOf[TensorInfo].getShape.lastOption)
      .getOrElse(0L)
    logger.info(s"Loaded ONNX model (size=$size inputs=$inputs outputs=$outputs dim=$dim)")
    OnnxSession(env, session, tokenizer, dim.toInt)
  }

  def loadFromHuggingFace(handle: HuggingFaceHandle, modelFile: String, vocabFile: String): IO[OnnxSession] = for {
    cache        <- LocalCache.create()
    modelDirName <- IO(handle.asList.mkString(File.separator))
    sbert <- HuggingFaceClient
      .create()
      .use(hf =>
        for {
          modelBytes <- cache.getIfExists(modelDirName, modelFile).flatMap {
            case Some(bytes) => info(s"found $modelFile in cache") *> IO.pure(bytes)
            case None => hf.modelFile(handle, modelFile).flatTap(bytes => cache.put(modelDirName, modelFile, bytes))
          }
          vocabBytes <- cache.getIfExists(modelDirName, vocabFile).flatMap {
            case Some(bytes) => info(s"found $vocabFile in cache") *> IO.pure(bytes)
            case None => hf.modelFile(handle, vocabFile).flatTap(bytes => cache.put(modelDirName, vocabFile, bytes))
          }
          session <- OnnxSession.load(
            model = new ByteArrayInputStream(modelBytes),
            dic = new ByteArrayInputStream(vocabBytes)
          )
        } yield {
          session
        }
      )
  } yield {
    sbert
  }

  def loadFromLocalDir(handle: LocalModelHandle, modelFile: String, vocabFile: String): IO[OnnxSession] = for {
    _          <- info(s"loading $modelFile from $handle")
    modelBytes <- IO(IOUtils.toByteArray(new FileInputStream(new File(handle.dir + File.separator + modelFile))))
    vocabBytes <- IO(IOUtils.toByteArray(new FileInputStream(new File(handle.dir + File.separator + vocabFile))))
    session    <- load(model = new ByteArrayInputStream(modelBytes), dic = new ByteArrayInputStream(vocabBytes))
  } yield {
    session
  }

}