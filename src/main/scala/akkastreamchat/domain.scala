package akkastreamchat

import scalapb.TypeMapper

object domain {

  final case class Username(name: String) extends AnyVal

  implicit val unm: TypeMapper[String, Username] =
    TypeMapper[String, Username](Username(_))(_.name)
}
