package java.security

// ported from Apache Harmony
// also used JavaDocs
// https://github.com/apache/harmony/blob/724deb045a85b722c961d8b5a83ac7a697319441/classlib/modules/security/src/main/java/common/java/security/Guard.java

trait Guard {
  @throws[SecurityException]
  def checkGuard(obj: scala.Any): Unit
}
