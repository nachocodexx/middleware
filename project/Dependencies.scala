import sbt._


object Dependencies {
  def apply(): Seq[ModuleID] = {
//    lazy val RabbitMQ = "dev.profunktor" %% "fs2-rabbit" % "4.0.0"
    lazy val Logback    = "ch.qos.logback" % "logback-classic" % "1.2.3"
    lazy val RabbitMQUtils = "mx.cinvestav" %% "rabbitmq-utils" % "0.3.3"
    val http4sVersion = "1.0.0-M23"
    lazy val Http4s =Seq(
     "org.http4s" %% "http4s-dsl" ,
     "org.http4s" %% "http4s-blaze-server" ,
     "org.http4s" %% "http4s-blaze-client",
    "org.http4s" %% "http4s-circe"
   ).map(_ % http4sVersion)
    lazy val PureConfig = "com.github.pureconfig" %% "pureconfig" % "0.15.0"
    lazy val MUnitCats ="org.typelevel" %% "munit-cats-effect-3" % "1.0.3" % Test
    lazy val Log4Cats =   "org.typelevel" %% "log4cats-slf4j"   % "2.1.1"
    lazy val ScalaCompress = "com.github.gekomad" %% "scala-compress" % "1.0.0"
    lazy val Commons = "mx.cinvestav" %% "commons" % "0.0.5"
    Seq(Logback,RabbitMQUtils,PureConfig,MUnitCats,Log4Cats,ScalaCompress,Commons)++Http4s
  }
}

