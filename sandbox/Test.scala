import scala.concurrent._


object Test {
  def main(args: Array[String]): Unit = {
    val ec =  scala.concurrent.ExecutionContext.Implicits.global
    scala.concurrent.ExecutionContext.fromExecutorService(new scala.concurrent.forkjoin.ForkJoinPool())
    println(s"golobal context ${ec.toString}")
    Future{ println("the future is here") } (ec)
    println("the future is coming")
    Thread.sleep(1000)
  }
}
