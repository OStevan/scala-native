package java.security


import java.util

@SerialVersionUID(739301742472979399L)
class BasicPermissionCollection(private var permClass: Class[_]) extends PermissionCollection {

//  @transient
  var items: util.Map[String, Permission] = new util.HashMap[String, Permission]()

  var allEnabled: Boolean = false

  // TODO check exception messages
  override def add(permission: Permission): Unit = {
    if (isReadOnly) {
      throw new SecurityException("Collection is read only")
    }

    if (permission == null)
      throw new IllegalArgumentException("Permission argument is null")

    val basicPermission = permission.asInstanceOf[BasicPermission]

    val inClass = basicPermission.getClass

    if (permClass != null  && permClass != inClass)
      throw new IllegalArgumentException("Permission collection can not hold the given permission")

    if (permClass == null) {
      this.synchronized {
        if (permClass != null && basicPermission.getClass != inClass)
          throw new IllegalArgumentException("Permission collection can not hold the given permission")

        permClass = inClass
      }
    }

    val name = permission.getName
    items.put(name, permission)
    allEnabled = allEnabled || (name.length == 1 && '*' == name.charAt(0))
  }

  override def implies(permission: Permission): Boolean = {
    if (permission == null || permission.getClass != permClass)
      return false

    if (allEnabled)
      return true
    val checkName = permission.getName

    if (items.containsKey(checkName))
      return true

    if (items.containsKey("exitVM") || items.containsKey("exitVM.*")) {
      if (checkName.endsWith("exitVM")) return true
      if (checkName.startsWith("exitVM.") && checkName.length > "exitVM.".length) return true
    }


    //now check if there are suitable wildcards
    //suppose we have "a.b.c", let's check "a.b.*" and "a.*"
    val name = checkName.toCharArray
    //I presume that "a.b.*" does not imply "a.b."
    //so the dot at end is ignored
    var pos = name.length - 2

    while (pos >= 0 && name(pos) != '.')
      pos -= 1

    while (pos >= 0) {
      name.update(pos + 1, '*')
      if (items.containsKey(new String(name, 0, pos + 2)))
        return true
      pos -= 1
      while (pos >= 0 && name(pos) != '.')
        pos -= 1
    }

    while (pos >= 0) {
      if (name(pos) == '.')
        pos = 0
      pos -= 1
    }
    true
  }

  override def elements(): util.Enumeration[Permission] = {
    util.Collections.enumeration(items.values())
  }

//  @throws[IOException]
//  private def writeObject(out: ObjectOutputStream): Unit = {
//    val fields: ObjectOutputStream.PutField = out.putFields()
//    fields.put("all_allowed", allEnabled)
//    fields.put("permissions", new util.Hashtable[String, Permission](items))
//    fields.put("permClass", permClass)
//    out.writeFields();
//  }
//
//  import java.io.InvalidObjectException
//  import java.io.ObjectInputStream
//
//  @throws[IOException]
//  @throws[ClassNotFoundException]
//  private def readObject(in: ObjectInputStream): Unit = {
//    val fields = in.readFields
//    items = new util.HashMap[String, Permission]()
//    permClass = fields.get("permClass", null).asInstanceOf[Class[_ <: Permission]] //$NON-NLS-1$
//
//    items.putAll(
//      fields.get("permissions", new util.Hashtable[String, Permission]()).
//        asInstanceOf[util.Hashtable[String, Permission]])
//
//    val iter = items.values.iterator
//    while (iter.hasNext)
//      if (iter.next.getClass ne permClass)
//        throw new InvalidObjectException("Reading a BasicPermissionCollection with an inconsistent state")
//
//    allEnabled = fields.get("all_allowed", false)
//    if (allEnabled && !items.containsKey("*")) throw new InvalidObjectException(
//      "Reading an incorrectly serialized BasicPermissionCollection")
//  }
}

object BasicPermissionCollection {
//  val serialPersistentFields: Array[ObjectStreamField] = Array(
//    new ObjectStreamField("all_allowed", Boolean.getClass),
//    new ObjectStreamField("permissions", ),
//    new ObjectStreamField("permClass", Class[Class])
//  )
}
