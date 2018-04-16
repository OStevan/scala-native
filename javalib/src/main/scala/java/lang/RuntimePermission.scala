package java.lang

import java.security.BasicPermission

class RuntimePermission(val name: String, val action: String) extends BasicPermission(name, action) {

  def this(name: String) = this(name, null)
}

object RuntimePermission {
  private val serialVersionUID = 7399184964622342223L

  /**
    * Constants for runtime permissions used in this package.
    */
  val permissionToSetSecurityManager = new RuntimePermission("setSecurityManager") //$NON-NLS-1$

  val permissionToCreateSecurityManager = new RuntimePermission("createSecurityManager")

  val permissionToGetProtectionDomain = new RuntimePermission("getProtectionDomain")

  val permissionToGetClassLoader = new RuntimePermission("getClassLoader")

  val permissionToCreateClassLoader = new RuntimePermission("createClassLoader")

  val permissionToModifyThread = new RuntimePermission("modifyThread")

  val permissionToModifyThreadGroup = new RuntimePermission("modifyThreadGroup")

  val permissionToExitVM = new RuntimePermission("exitVM")

  val permissionToReadFileDescriptor = new RuntimePermission("readFileDescriptor")

  val permissionToWriteFileDescriptor = new RuntimePermission("writeFileDescriptor")

  val permissionToQueuePrintJob = new RuntimePermission("queuePrintJob")

  val permissionToSetFactory = new RuntimePermission("setFactory")

  val permissionToSetIO = new RuntimePermission("setIO")

  val permissionToStopThread = new RuntimePermission("stopThread")

  val permissionToSetContextClassLoader = new RuntimePermission("setContextClassLoader")
}