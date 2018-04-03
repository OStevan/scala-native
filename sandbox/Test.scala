object Test {
  def main(args: Array[String]): Unit = {
    val int: Int = 5
    int.synchronized {
      println("Hello, World!")
    }
  }
}
