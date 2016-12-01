/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit

import org.scalactic.Constraint

import language.postfixOps
import org.scalatest.{ BeforeAndAfterAll, WordSpecLike }
import org.scalatest.Matchers
import akka.actor.ActorSystem
import akka.event.{ Logging, LoggingAdapter }

import scala.concurrent.duration._
import scala.concurrent.Future
import com.typesafe.config.{ Config, ConfigFactory }
import akka.dispatch.Dispatchers
import akka.testkit.TestEvent._
import org.scalactic.ConversionCheckedTripleEquals
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Span

object AkkaSpec {
  val testConf: Config = ConfigFactory.load()

  akka.actor.JSDynamicAccess.injectClass(
    "akka.testkit.TestEventListener" -> classOf[akka.testkit.TestEventListener]
  )

  def mapToConfig(map: Map[String, Any]): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(map.asJava)
  }

  def getCallerName(clazz: Class[_]): String = {
    /*
    val s = (Thread.currentThread.getStackTrace map (_.getClassName) drop 1)
      .dropWhile(_ matches "(java.lang.Thread|.*AkkaSpec.?$|.*StreamSpec.?$)")
    val reduced = s.lastIndexWhere(_ == clazz.getName) match {
      case -1 ⇒ s
      case z  ⇒ s drop (z + 1)
    }
    reduced.head.replaceFirst(""".*\.""", "").replaceAll("[^a-zA-Z_0-9]", "_")
    */
    java.util.UUID.randomUUID.toString.replace("-","")
  }

}

abstract class AkkaSpec(_system: ActorSystem)
  extends TestKit(_system) with WordSpecLike with Matchers with BeforeAndAfterAll
  //with WatchedByCoroner
  with ConversionCheckedTripleEquals with ScalaFutures {

  implicit val patience = PatienceConfig(testKitSettings.DefaultTimeout.duration, Span(100, org.scalatest.time.Millis))

  def this(config: Config) = this(ActorSystem(
    AkkaSpec.getCallerName(getClass),
    ConfigFactory.load(config.withFallback(AkkaSpec.testConf))))

  def this(s: String) = this(ConfigFactory.parseString(s))

  def this(configMap: Map[String, _]) = this(AkkaSpec.mapToConfig(configMap))

  //def this() = this(ActorSystem(AkkaSpec.getCallerName(getClass), AkkaSpec.testConf))
  def this() = {
    this({
      ManagedEventLoop.manage
      val sys = ActorSystem(AkkaSpec.getCallerName(getClass), AkkaSpec.testConf)
      val p = scala.concurrent.Promise[Unit]
      import sys.dispatcher
      sys.scheduler.scheduleOnce(0 millis){
        p.success(())
      }
      Await.result(p.future, 10 seconds)
      sys
    })
  }

  val log: LoggingAdapter = Logging(system, this.getClass)

  override val invokeBeforeAllAndAfterAllEvenIfNoTestsAreExpected = true

  final override def beforeAll {
    //startCoroner
    atStartup()
  }

  final override def afterAll {
    beforeTermination()
    shutdown()
    afterTermination()
    //stopCoroner()
  }

  protected def atStartup() {}

  protected def beforeTermination() {}

  protected def afterTermination() {}

  def spawn(dispatcherId: String = Dispatchers.DefaultDispatcherId)(body: ⇒ Unit): Unit =
    Future(body)(system.dispatchers.lookup(dispatcherId))

  /*override*/ def expectedTestDuration: FiniteDuration = 60 seconds

  def muteDeadLetters(messageClasses: Class[_]*)(sys: ActorSystem = system): Unit =
    if (!sys.log.isDebugEnabled) {
      def mute(clazz: Class[_]): Unit =
        sys.eventStream.publish(Mute(DeadLettersFilter(clazz)(occurrences = Int.MaxValue)))
      if (messageClasses.isEmpty) mute(classOf[AnyRef])
      else messageClasses foreach mute
    }

  // for ScalaTest === compare of Class objects
  implicit def classEqualityConstraint[A, B]: Constraint[Class[A], Class[B]] =
    new Constraint[Class[A], Class[B]] {
      def areEqual(a: Class[A], b: Class[B]) = a == b
    }

  implicit def setEqualityConstraint[A, T <: Set[_ <: A]]: Constraint[Set[A], T] =
    new Constraint[Set[A], T] {
      def areEqual(a: Set[A], b: T) = a == b
    }
}