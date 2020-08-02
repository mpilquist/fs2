package fs2

sealed trait NotNothing[-A]
object NotNothing {
  implicit val nothing1: NotNothing[Nothing] = new NotNothing[Nothing] {}
  implicit val nothing2: NotNothing[Nothing] = new NotNothing[Nothing] {}
  implicit def notNothing[A]: NotNothing[A] = new NotNothing[A] {}
}
