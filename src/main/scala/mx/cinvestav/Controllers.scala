package mx.cinvestav

import cats.implicits._
import cats.effect.IO
import fs2.{Pipe, Stream}
import mx.cinvestav.Main.processFile
import mx.cinvestav.utils.Command
import org.http4s.{MediaType, Request, Response}
import org.http4s.dsl.io.Forbidden
import org.http4s.multipart.Multipart
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.Text
import org.typelevel.ci.{CIString, CIStringSyntax}
import org.typelevel.log4cats.Logger
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.circe._
import io.circe.parser.parse

import concurrent.duration._
import language.postfixOps
import org.http4s.dsl.io._
import org.http4s.headers.{`Content-Disposition`, `Content-Type`}
//import org.http4s.AuthedRequest.

import java.nio.charset.StandardCharsets
import java.util.Base64.getDecoder
import java.util.UUID

object Controllers {
  import Declarations._


  def hello(req:Request[IO])(implicit ctx:AppContext):IO[Response[IO]] =
    Ok(s"Hello I'm ${ctx.config.nodeId}")
  def ws(req:Request[IO])(implicit ctx:AppContext): IO[Response[IO]] = {
    val toClient:Stream[IO,WebSocketFrame] = Stream.awakeEvery[IO](1 seconds).map{ d=>
      Text(s"Pink $d")
    }
    val fromClient: Pipe[IO,WebSocketFrame,Unit] = _.evalMap {
      case WebSocketFrame.Binary(data, last) => ctx.logger.debug("Binary")
      case WebSocketFrame.Continuation(data, last) => ctx.logger.debug("Continuation")
      case frame: WebSocketFrame.ControlFrame => ctx.logger.debug("Constrol FRAME")
      case text: Text => ctx.logger.debug(s"TEXT: $text")
      case _ => IO.unit
    }
    WebSocketBuilder[IO].build(send =toClient ,receive = fromClient)
  }
  def _upload(req:Request[IO],multipart: Multipart[IO])(implicit ctx:AppContext):IO[Response[IO]] = {
    for {
      currentState <- ctx.state.get
      parts        = multipart.parts.toList
      streams      = parts.map(_.body)
      headers      = req.headers
      maybeUserId  = headers.get(ci"userId").map(_.head).map(_.value).map(UUID.fromString)
      res <- maybeUserId match {
        case Some(userId) =>
          for {
            _ <- IO.unit
            maybePayload = parts.map(_.headers.get(ci"payload")).sequence
            res          <- maybePayload match {
              case Some(payloads) =>
                val BASE64Decoder = getDecoder
                val maybeJsons   = payloads.map(_.head)
                  .map(_.value)
                  .map(BASE64Decoder.decode)
                  .map(new String(_,StandardCharsets.UTF_8))
                  .map(parse)
                  .sequence
                maybeJsons.flatMap(_.map(_.as[FilePayload]).sequence) match {
                  case Left(e) =>  ctx.logger.error(e.getMessage) *> BadRequest()
                  case Right(fileUploads) =>
                    val events = (fileUploads zip streams).map{
                      case (fp,body)=>
                      UploadEvent(
                        commandId = UUID.randomUUID(),
                        userId = userId,
                        fileId=UUID.fromString(fp.fileId),
                        filename=fp.filename,
                        name=fp.name,
                        extension=fp.extension,
                        body= body,
                        timestamp = fp.timestamp
                      )
                    }
                    val emitEventsIO = Stream.emits(events).evalMap(currentState.q.offer).compile.drain
                    val responseIO = Ok()
                    for {
                      _        <- emitEventsIO
                      response <- responseIO
                    } yield response
                }
              case None => BadRequest()
            }
          } yield res
        case None => Forbidden()
      }

    } yield res
  }


  def extractUserId(req:Request[IO])= for {
    _ <- IO.unit
    userIdHeaderName = CIString("user-id")
    maybeUserId = req.headers.get(userIdHeaderName).map(_.head.value)
  } yield maybeUserId
  def upload(req:Request[IO])(implicit ctx:AppContext):IO[Response[IO]] = {
    req.decode[Multipart[IO]]{ m=>
      for {
        _ <- IO.unit
        maybeUserId <- extractUserId(req=req)
        res         <-  maybeUserId match {
          case Some(userId) => Ok
            for {
              result <- processFile(userId,m)
            } yield result
          case None => Forbidden()
        }
      } yield res
    }
  }

}
