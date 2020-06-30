package io.janstenpickle.trace4cats.agent

import cats.effect.{Blocker, ExitCode, IO, Resource}
import cats.implicits._
import com.monovore.decline._
import com.monovore.decline.effect._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.trace4cats.avro._
import io.janstenpickle.trace4cats.avro.server.AvroServer
import io.janstenpickle.trace4cats.model.TraceProcess

object Agent extends CommandIOApp(name = "trace4cats-agent", header = "Trace 4 Cats Agent", version = "0.1.0") {

  val portOpt: Opts[Int] =
    Opts
      .env[Int](AgentPortEnv, help = "The port to run on.")
      .orElse(Opts.option[Int]("port", "The port to run on"))
      .orNone
      .map(_.getOrElse(DefaultPort))

  val collectorHostOpt: Opts[String] =
    Opts
      .env[String](CollectorHostEnv, "Collector hostname to forward spans")
      .orElse(Opts.option[String]("collector", "Collector hostname"))

  val collectorPortOpt: Opts[Int] =
    Opts
      .env[Int](CollectorPortEnv, "Collector port to forward spans")
      .orElse(Opts.option[Int]("collector-port", "Collector port"))
      .orNone
      .map(_.getOrElse(DefaultPort))

  override def main: Opts[IO[ExitCode]] = (portOpt, collectorHostOpt, collectorPortOpt).mapN(run)

  def run(port: Int, collectorHost: String, collectorPort: Int): IO[ExitCode] =
    (for {
      blocker <- Blocker[IO]
      logger <- Resource.liftF(Slf4jLogger.create[IO])
      _ <- Resource.make(
        logger
          .info(s"Starting Trace 4 Cats Agent on udp://::$port. Forwarding to tcp://$collectorHost:$collectorPort")
      )(_ => logger.info("Shutting down Trace 4 Cats Agent"))

      avroCompleter <- AvroSpanCompleter
        .tcp[IO](blocker, TraceProcess("trace4cats-agent"), host = collectorHost, port = collectorPort)

      udpServer <- AvroServer.udp[IO](blocker, _.evalMap(avroCompleter.completeBatch), port)
    } yield udpServer).use(_.compile.drain.as(ExitCode.Success))
}