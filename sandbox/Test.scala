import scala.scalanative.native.{CLong, CCast, Ptr}

object Test {


  class PointerTestClass() {
    val value: CLong = 3L
    val value1: CLong = 1L
  }

  def main(args: Array[String]): Unit = {
    println("Hello world!")
  }
}
