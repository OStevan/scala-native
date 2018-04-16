package java.security

import java.io.{IOException, ObjectInputStream}

// ported from Apache Harmony
// source code https://github.com/apache/harmony/blob/724deb045a85b722c961d8b5a83ac7a697319441/classlib/modules/security/src/main/java/common/java/security/BasicPermission.java

abstract class BasicPermission(name: String) extends Permission(name) with Serializable {
  checkName(name)

  def this(name: String, action: String) = this(name)

  // TODO check HARMONY messages
  private def checkName(name: String): Unit = {
    if (name == null)
      throw new NullPointerException("BasicPermission name is null")

    if (name.length == 0)
      throw new IllegalArgumentException("BasicPermission name is empty string")
  }

  override def equals(obj: scala.Any): Boolean = {
    if (obj == this) {
      return true
    }

    if (obj != null && obj.getClass == this.getClass) {
      return this.getName.equals(obj.asInstanceOf[Permission].getName)
    }

    false
  }

  override def hashCode(): Int = getName.hashCode

  override def getActions: String = ""

  override def implies(permission: Permission): Boolean = {
    if (permission != null && permission.getClass == this.getClass) {
      var name: String = getName
      var thatName: String = permission.getName
      if (this.isInstanceOf[RuntimePermission]) {
        if (thatName.equals("exitVM")) {
          thatName = "exitVM.*"
        }
        if (name.equals("exitVM"))
          name = "exitVM.*"
      }
      BasicPermission.nameImplies(name, thatName)
    } else false
  }

  override def newPermissionCollection(): PermissionCollection = {
    new BasicPermissionCollection(this.getClass)
  }

//  @throws[IOException]
//  @throws[ClassNotFoundException]
//  private def readObject(in: ObjectInputStream): Unit = {
//    in.defaultReadObject()
//    checkName(this.getName)
//  }
}

object BasicPermission {
  val serialVersionUID: Long = 6279438298436773498L

  def nameImplies(thisName: String, thatName: String): Boolean = {
    if (thisName == thatName)
      return true

    var end: Int = thisName.length - 1
    if (end > thatName.length)
      return false

    if (thisName.charAt(end) == '*' &&
      (end == 0 || thisName.charAt(end - 1) == '.')) {
      end = end - 1
    } else if (end != (thatName.length - 1)) {
      return false
    }

    while (end != 0) {
      if (thisName.charAt(end) != thatName.charAt(end))
        return false
      end = end - 1
    }
    true
  }
}
