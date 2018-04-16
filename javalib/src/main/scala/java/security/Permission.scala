package java.security

import java.io.Serializable

// ported from Harmony
// used JavaDocs
// https://github.com/apache/harmony/blob/724deb045a85b722c961d8b5a83ac7a697319441/classlib/modules/security/src/main/java/common/java/security/Permission.java

abstract class Permission(private val name: String) extends Guard with Serializable {

  override def equals(obj: scala.Any): Boolean

  override def hashCode(): Int

  def getActions(): String

  def implies(permission: Permission): Boolean

  def getName(): String = name

  @throws[SecurityException]
  override def checkGuard(obj: scala.Any): Unit = {
    val sm: SecurityManager = System.getSecurityManager
    if (sm != null) {
      sm.checkPermission(this)
    }
  }

  def newPermissionCollection(): PermissionCollection = null


  override def toString: String = {
    var actions = getActions()
    actions = if (actions == null || actions.length() == 0) {
      ""
    } else {
      " " + getActions()
    }
    "(" + getClass.getName + " " + getName() + actions + ")"
  }
}

object Permission {
  private val serialVersionUID: Long = -5636570222231596674L
}
