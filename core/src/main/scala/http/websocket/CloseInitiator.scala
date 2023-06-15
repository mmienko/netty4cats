package cats.netty
package http.websocket

import cats.Show

sealed abstract class CloseInitiator extends Product with Serializable

object CloseInitiator {

  implicit val show: Show[CloseInitiator] = Show.show {
    case Client => "client"
    case Server => "server"
    case Application => "application"
  }

  case object Client extends CloseInitiator
  case object Server extends CloseInitiator
  case object Application extends CloseInitiator
}
