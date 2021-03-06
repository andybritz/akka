/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import language.postfixOps
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.event.Logging.DefaultLogger

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ConfigSpec extends AkkaSpec(ConfigFactory.defaultReference(ActorSystem.findClassLoader())) {

  "The default configuration file (i.e. reference.conf)" must {
    "contain all configuration properties for akka-actor that are used in code with their correct defaults" in {

      val settings = system.settings
      val config = settings.config

      {
        import config._

        getString("akka.version") should equal("2.3-SNAPSHOT")
        settings.ConfigVersion should equal("2.3-SNAPSHOT")

        getBoolean("akka.daemonic") should equal(false)

        // WARNING: This setting should be off in the default reference.conf, but should be on when running
        // the test suite.
        getBoolean("akka.actor.serialize-messages") should equal(true)
        settings.SerializeAllMessages should equal(true)

        getInt("akka.scheduler.ticks-per-wheel") should equal(512)
        getMilliseconds("akka.scheduler.tick-duration") should equal(10)
        getString("akka.scheduler.implementation") should equal("akka.actor.LightArrayRevolverScheduler")

        getBoolean("akka.daemonic") should be(false)
        settings.Daemonicity should be(false)

        getBoolean("akka.jvm-exit-on-fatal-error") should be(true)
        settings.JvmExitOnFatalError should be(true)

        getInt("akka.actor.deployment.default.virtual-nodes-factor") should be(10)
        settings.DefaultVirtualNodesFactor should be(10)

        getMilliseconds("akka.actor.unstarted-push-timeout") should be(10.seconds.toMillis)
        settings.UnstartedPushTimeout.duration should be(10.seconds)

        settings.Loggers.size should be(1)
        settings.Loggers.head should be(classOf[DefaultLogger].getName)
        getStringList("akka.loggers").get(0) should be(classOf[DefaultLogger].getName)

        getMilliseconds("akka.logger-startup-timeout") should be(5.seconds.toMillis)
        settings.LoggerStartTimeout.duration should be(5.seconds)

        getInt("akka.log-dead-letters") should be(10)
        settings.LogDeadLetters should be(10)

        getBoolean("akka.log-dead-letters-during-shutdown") should be(true)
        settings.LogDeadLettersDuringShutdown should be(true)
      }

      {
        val c = config.getConfig("akka.actor.default-dispatcher")

        //General dispatcher config

        {
          c.getString("type") should equal("Dispatcher")
          c.getString("executor") should equal("fork-join-executor")
          c.getMilliseconds("shutdown-timeout") should equal(1 * 1000)
          c.getInt("throughput") should equal(5)
          c.getMilliseconds("throughput-deadline-time") should equal(0)
          c.getBoolean("attempt-teamwork") should equal(true)
        }

        //Fork join executor config

        {
          val pool = c.getConfig("fork-join-executor")
          pool.getInt("parallelism-min") should equal(8)
          pool.getDouble("parallelism-factor") should equal(3.0)
          pool.getInt("parallelism-max") should equal(64)
        }

        //Thread pool executor config

        {
          val pool = c.getConfig("thread-pool-executor")
          import pool._
          getMilliseconds("keep-alive-time") should equal(60 * 1000)
          getDouble("core-pool-size-factor") should equal(3.0)
          getDouble("max-pool-size-factor") should equal(3.0)
          getInt("task-queue-size") should equal(-1)
          getString("task-queue-type") should equal("linked")
          getBoolean("allow-core-timeout") should equal(true)
        }

        // Debug config
        {
          val debug = config.getConfig("akka.actor.debug")
          import debug._
          getBoolean("receive") should be(false)
          settings.AddLoggingReceive should be(false)

          getBoolean("autoreceive") should be(false)
          settings.DebugAutoReceive should be(false)

          getBoolean("lifecycle") should be(false)
          settings.DebugLifecycle should be(false)

          getBoolean("fsm") should be(false)
          settings.FsmDebugEvent should be(false)

          getBoolean("event-stream") should be(false)
          settings.DebugEventStream should be(false)

          getBoolean("unhandled") should be(false)
          settings.DebugUnhandledMessage should be(false)

          getBoolean("router-misconfiguration") should be(false)
          settings.DebugRouterMisconfiguration should be(false)
        }

      }

      {
        val c = config.getConfig("akka.actor.default-mailbox")

        // general mailbox config

        {
          c.getInt("mailbox-capacity") should equal(1000)
          c.getMilliseconds("mailbox-push-timeout-time") should equal(10 * 1000)
          c.getString("mailbox-type") should be("akka.dispatch.UnboundedMailbox")
        }
      }
    }
  }
}
