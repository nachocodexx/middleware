package mx.cinvestav

import cats.effect.{IO, Ref}
import cats.effect.std.Queue
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.AMQPConnection
import fs2.Stream
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.auto._
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax._
import org.typelevel.log4cats.Logger

import java.util.UUID

object Declarations {
  case class User(
                   id:UUID,
                   firstName:String,
                   lastName:String,
                   profilePhoto:String,
                   role:String,
                   password:Array[Byte],
                   sessionToken:Array[Byte]
                 )
//
  case class FilePayload(
                          fileId:String,
                          filename:String,
                          name:String,
                          extension:String,
                          size:Long,
                          compressionAlgorithm:String="LZ4",
                          timestamp:Long
                        )
  implicit val filePayloadEncoder:Encoder[FilePayload] = deriveEncoder

  implicit val filePayloadDecoder:Decoder[FilePayload] = deriveDecoder
  case class Metadata(fileId:UUID,
                      userId:UUID,
                      filename:String,
                      name:String,
                      extension:String,
                      size:Long,
                      compressionAlgorithm:String="LZ4",
                      timestamp:Long
                     )
  implicit val metadataEncoder:Encoder[Metadata] = (m:Metadata) => Json.obj(
    ("fileId"->m.fileId.toString.asJson),
    ("userId"-> m.userId.asJson),
    ("filename"-> m.filename.asJson),
    ("name"-> m.name.asJson),
    ("extension"-> m.extension.asJson),
    ("size"-> m.size.asJson),
    ("compressionAlgorithm"-> m.compressionAlgorithm.asJson),
    ("timestamp"-> m.timestamp.asJson),
  )

  trait Event {
    def commandId:UUID
  }
  case class UploadEvent(
                            commandId:UUID,
                            userId:UUID,
                            fileId:UUID,
                            filename:String,
                            name:String,
                            extension:String,
                            body:Stream[IO,Byte],
                            timestamp:Long
                          ) extends Event
  case class DownloadEvent(
                              commandId:UUID,
                              fileId:UUID,
                            ) extends Event

  case class NodeState(q:Queue[IO,Event], metadata:Map[String,Metadata])
  case class RabbitMQContext(
                              client:RabbitClient[IO],
                              connection:AMQPConnection
                            )
  case class AppContext(config:DefaultConfig,state:Ref[IO,NodeState],rabbitMQContext: RabbitMQContext,logger: Logger[IO])

}
