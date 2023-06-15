package cats.netty
package http

import cats.Show
import org.slf4j.{MDC => sl4jMDC}

object Logging {

  val showError: Show[Throwable] = Show.show { cause =>
    s"${cause.toString}\n\t${Option(cause.getCause).fold("")(_.toString + "\n\t")}${cause.getStackTrace.take(20).mkString("\n\t")}"
  }

  /**
    * Common MDC keys to keep consistency between classes. MDC pattern will be removed once
    * https://github.com/logfellow/logstash-logback-encoder/pull/954 is merged which supports fluent
    * api.
    */
  object MDC {
    def putServerName(name: String): Unit = sl4jMDC.put("serverName", name)
    def putPipelineEvent(name: String): Unit = sl4jMDC.put("event", name)
  }

}
