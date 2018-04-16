package java.security


import java.util

@SerialVersionUID(-6727011328946861783L)
abstract class PermissionCollection extends Serializable { // when set, add will throw an exception.
  private var readOnly: Boolean = false

  def add(permission: Permission): Unit

  def implies(permission: Permission): Boolean

  def elements: util.Enumeration[Permission]

  def setReadOnly(): Unit = {
    readOnly = true
  }

  def isReadOnly: Boolean = readOnly

  override def toString: String = {
    val elist: util.List[String] = new util.ArrayList[String](100)
    val elenum: util.Enumeration[Permission] = elements
    val superStr: String = super.toString
    var totalLength = superStr.length + 5
    if (elenum != null) {
      while (elenum.hasMoreElements) {
        val el = elenum.nextElement().toString
        totalLength += el.length
        elist.add(el)
      }
    }
    val esize = elist.size()
    totalLength += esize * 4
    val builder = new StringBuilder(totalLength).append(superStr).append("(")

    for (i <- 0 to esize) {
      builder.append("\n ").append(elist.get(i).toString)
    }

    builder.append("\n)\n").toString
  }
}