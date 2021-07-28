package mx.cinvestav

import cats.implicits._
import cats.data.{Kleisli, OptionT}
import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{ExitCode, IO, IOApp, Ref}
import com.github.gekomad.scalacompress.Compressors.{StreamCompress, StreamableCompressor}
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import org.http4s.{Header, HttpRoutes, Request, Response}
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.server.{AuthMiddleware, Router}
import org.http4s.implicits._
import org.http4s._
import org.http4s.dsl.io._
import fs2.{Pipe, Stream}
import org.typelevel.ci.CIString
import pureconfig.ConfigSource

import java.io.{File, FileOutputStream}
import java.util.UUID
//
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.ExecutionContext.global
import mx.cinvestav.utils.RabbitMQUtils
import org.http4s.multipart.Multipart
import org.http4s.server.websocket.WebSocketBuilder
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.generic.auto._
import mx.cinvestav.commons.compression
import Declarations._

object Main extends IOApp{
  implicit val config:DefaultConfig = ConfigSource.default.loadOrThrow[DefaultConfig]
  implicit val unsafeLogger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  val fs2RabbitConfig: Fs2RabbitConfig = Fs2RabbitConfig(
    host = "localhost",
    port = 5672,
    virtualHost = "/",
    connectionTimeout = 3 seconds,
    ssl = false,
    username = None,
    password = None,
    requeueOnNack = false,
    requeueOnReject = false,
    internalQueueSize = Some(500),
  )

  def getExtensionFromStr(x:String): String = x.toString.split("\\.").last

  def compress(data:Stream[IO,Byte],streamCompress: StreamCompress) = for {
      _ <- IO.unit
      _streamCompress = IO.delay{streamCompress}
      compressStream  = (streamCompress:StreamCompress)=> data.chunkN(4098).map(_.toArray)
        .map(streamCompress.compressBuffer)
        .onComplete{
          Stream.eval(IO.fromTry(streamCompress.close()))
        }
        .compile.drain

      _  <- for {
        sc <- _streamCompress
        _ <- compressStream(sc)
      } yield ()
    } yield ()

  def processFile(userId:String,m:Multipart[IO])(implicit ctx:AppContext): IO[Response[IO]] = for {
    _        <- Logger[IO].debug("PROCESS FILE")
    parts    = m.parts
    headerName = CIString("Content-Disposition")
    headers  = parts.traverse(_.headers.get(headerName))
    response      <- headers match {
      case Some(rawHeaders) =>
        for {
          _         <- IO.unit
          streams   = parts.map(_.body).toList
          _         <- Logger[IO].debug(headers.mkString(","))
          metadata  = rawHeaders
            .map(_.head.value.split(';'))
            .map { xs =>
              val res = xs.map(_.split('='))
                .filter(xs => xs.length != 1 && xs.nonEmpty)
                .map{xs=>
                  val fieldName = xs(0).trim
                  val value = xs(1).trim.replace("\"", "");
                    (fieldName,value)
                }.toMap
              val filename = res.get("filename")
              val extension = filename.map(getExtensionFromStr)
              res + ("extension"->extension.getOrElse(""))
            }.toList
          _ <- Logger[IO].debug(metadata.mkString(","))
          streamsWithMetadata <- (streams zip metadata).traverse{
            case (stream,metadata) =>
              val randomFilename = UUID.randomUUID().toString
              val filename = metadata.getOrElse("name",randomFilename)
              val extension = metadata.getOrElse("extension","")
              val compressionAlgorithm = StreamableCompressor.LZ4.toString
              val compressionExt =compression.fromString(compressionAlgorithm).extension
              val userFolder = new File(config.storagePath+s"/compressed/$userId")
              for {
                  _ <- IO.unit
                _ <- if(!userFolder.exists())
                  IO.delay{userFolder.mkdir()}
                else  IO.unit
                  outputPath = userFolder.getPath+s"/$filename.$compressionExt"
                sc =  StreamCompress(compressorName = StreamableCompressor.LZ4, new FileOutputStream(""))
//                  compress(stream,)
              } yield ()

//              for {
//                _ <- IO.unit
//                streamCompress = IO.delay{StreamCompress(
//                  StreamableCompressor.withName(compressionAlgorithm),
//                  new FileOutputStream(s"${userFolder.getPath}/$filename.$compressionExt")
//                )}
//                compressStream  = (streamCompress:StreamCompress)=> stream.chunkN(4098).map(_.toArray)
//                  .map{ data=>
//                    streamCompress.compressBuffer(data)
//                  }
//                  .onComplete{
//                    Stream.eval(IO.fromTry(streamCompress.close()))
//                  }
//                  .compile.drain
//
//                compress  = for {
//                     sc <- streamCompress
//                     _ <- compressStream(sc)
//                } yield ()
//                _ <- if(!userFolder.exists()) for {
//                  _ <- IO.delay{userFolder.mkdir()}
//                  _ <- compress
//                } yield ()
//                else  compress
//              } yield ()

          }

          response <- Ok("OK")
        } yield response
      case None => BadRequest()

    }
  } yield response


//  case class User
  def authUser:Kleisli[OptionT[IO,*],Request[IO],User] =
    Kleisli{ req=>
//      OptionT.fromOption[IO](None)
      OptionT.liftF{
      User(
        id           = UUID.randomUUID(),
        firstName    = "Guest",
        lastName     = "Guest",
        profilePhoto = "http://localhost:7000/profile_photo.png",
        role         = "user",
        password     = Array.emptyByteArray,
        sessionToken = Array.emptyByteArray
      ).pure[IO]
      }
    }
  def authMiddleware:AuthMiddleware[IO,User] = AuthMiddleware(authUser=authUser)
  def authRoutes():AuthedRoutes[User,IO] = AuthedRoutes.of{
    case GET -> Root as user =>
      IO.println(user)*>Ok("TOP SECRET")
  }

  def routes()(implicit ctx:AppContext):HttpRoutes[IO] = HttpRoutes.of{
    case req@GET -> Root => Controllers.hello(req=req)
    case req @POST -> Root / "upload" =>  req.decode[Multipart[IO]]{  m=>
      Controllers._upload(req=req,multipart = m)
    }
    case req @ GET  -> Root / "ws" => Controllers.ws(req=req)
  }


  def httpApp()(implicit ctx:AppContext): Kleisli[IO, Request[IO],
    Response[IO]] =
    Router[IO](
    "/" -> routes(),
      "/auth" -> authMiddleware(authRoutes())
  )
      .orNotFound

  def server()(implicit ctx:AppContext): IO[Unit] =
    BlazeServerBuilder[IO](executionContext = global)
      .bindHttp(port = config.port,host=config.host)
      .withHttpApp(httpApp())
      .serve
      .compile
      .drain

  override def run(args: List[String]): IO[ExitCode] = {
    RabbitMQUtils.initV2[IO](config = fs2RabbitConfig){ implicit client=>
     for {
       _ <- IO.unit
       _ <- client.createConnection.use{ connection=>
         for {
           _         <- IO.unit
           rabbitMQ  = RabbitMQContext(client = client, connection=connection)
           q         <- Queue.bounded[IO,Event](10000)
           initState = NodeState(q= q,metadata = Map.empty[String,Metadata])
           state     <-  IO.ref(initState)
           ctx    = AppContext(config = config,state =state , rabbitMQContext = rabbitMQ,logger = unsafeLogger)
           _         <- Stream.fromQueueUnterminated(queue = q)
             .groupWithin(100,5 seconds).flatMap{ events=>
                  Stream.evalUnChunk(events.pure[IO])
                    .evalMap {
                      case DownloadEvent(commandId, fileId) => ctx.logger.debug("DOWNLOAD")
                      case UploadEvent(commandId, userId, fileId, filename, name, extension, body, timestamp) =>
                        ctx.logger.debug(s"UPLOAD $commandId")
                      case _ => ctx.logger.debug("UKNOWN")
                    }
             }
             .compile.drain.start
           _         <- server()(ctx=ctx)
         } yield ()
       }
     }  yield ()
    }.as(ExitCode.Success)
  }
}
