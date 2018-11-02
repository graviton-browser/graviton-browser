package net.plan99.graviton.mac

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * A JNA wrapper around the objective-c runtime.  This contains all of the functions
 * needed to interact with the runtime (e.g. send messages, etc..).
 *
 * <h3>Sample Usage</h3>
 * <script src="https://gist.github.com/3974488.js?file=SampleLowLevelAPI.java"></script>
 *
 * @author shannah
 * @see [Objective-C Runtime Reference](https://developer.apple.com/library/mac/.documentation/Cocoa/Reference/ObjCRuntimeRef/Reference/reference.html)
 */
@Suppress("FunctionName")
interface ObjectiveCRuntime : Library {
    fun objc_lookUpClass(name: String): Pointer
    fun class_getName(id: Pointer): String
    fun class_getProperty(cls: Pointer, name: String): Pointer
    fun class_getSuperclass(cls: Pointer): Pointer
    fun class_getVersion(theClass: Pointer): Int
    fun class_getWeakIvarLayout(cls: Pointer): String
    fun class_isMetaClass(cls: Pointer): Boolean
    fun class_getInstanceSize(cls: Pointer): Int
    fun class_getInstanceVariable(cls: Pointer, name: String): Pointer
    fun class_getInstanceMethod(cls: Pointer, aSelector: Pointer): Pointer
    fun class_getClassMethod(cls: Pointer, aSelector: Pointer): Pointer

    fun class_getIvarLayout(cls: Pointer): String
    fun class_getMethodImplementation(cls: Pointer, name: Pointer): Pointer
    fun class_getMethodImplementation_stret(cls: Pointer, name: Pointer): Pointer
    fun class_replaceMethod(cls: Pointer, name: Pointer, imp: Pointer, types: String): Pointer
    fun class_respondsToSelector(cls: Pointer, sel: Pointer): Pointer
    fun class_setIvarLayout(cls: Pointer, layout: String)
    fun class_setSuperclass(cls: Pointer, newSuper: Pointer): Pointer
    fun class_setVersion(theClass: Pointer, version: Int)
    fun class_setWeakIvarLayout(cls: Pointer, layout: String)
    fun ivar_getName(ivar: Pointer): String
    fun ivar_getOffset(ivar: Pointer): Long
    fun ivar_getTypeEncoding(ivar: Pointer): String
    fun method_copyArgumentType(method: Pointer, index: Int): String
    fun method_copyReturnType(method: Pointer): String
    fun method_exchangeImplementations(m1: Pointer, m2: Pointer)
    fun method_getArgumentType(method: Pointer, index: Int, dst: Pointer, dst_len: Long)
    fun method_getImplementation(method: Pointer): Pointer
    fun method_getName(method: Pointer): Pointer
    fun method_getNumberOfArguments(method: Pointer): Int
    fun method_getReturnType(method: Pointer, dst: Pointer, dst_len: Long)
    fun method_getTypeEncoding(method: Pointer): String
    fun method_setImplementation(method: Pointer, imp: Pointer): Pointer
    fun objc_allocateClassPair(superclass: Pointer, name: String, extraBytes: Long): Pointer
    fun objc_copyProtocolList(outCount: Pointer): Array<Pointer>
    fun objc_getAssociatedObject(`object`: Pointer, key: String): Pointer
    fun objc_getClass(name: String): Pointer
    fun objc_getClassList(buffer: Pointer, bufferlen: Int): Int
    fun objc_getFutureClass(name: String): Pointer
    fun objc_getMetaClass(name: String): Pointer
    fun objc_getProtocol(name: String): Pointer
    fun objc_getRequiredClass(name: String): Pointer
    fun objc_msgSend(theReceiver: Pointer, theSelector: Pointer, vararg arguments: Any): Long

    fun objc_msgSendSuper(superClassStruct: Pointer, op: Pointer, vararg arguments: Any): Long
    fun objc_msgSendSuper_stret(superClassStruct: Pointer, op: Pointer, vararg arguments: Any): Long
    fun objc_msgSend_fpret(self: Pointer, op: Pointer, vararg arguments: Any): Double
    fun objc_msgSend_stret(stretAddr: Pointer, theReceiver: Pointer, theSelector: Pointer, vararg arguments: Any)
    fun objc_registerClassPair(cls: Pointer)
    fun objc_removeAssociatedObjects(`object`: Pointer)
    fun objc_setAssociatedObject(`object`: Pointer, key: Pointer, value: Pointer, policy: Pointer)
    fun objc_setFutureClass(cls: Pointer, name: String)
    fun object_copy(obj: Pointer, size: Long): Pointer
    fun object_dispose(obj: Pointer): Pointer
    fun object_getClass(`object`: Pointer): Pointer
    fun object_getClassName(obj: Pointer): String
    fun object_getIndexedIvars(obj: Pointer): Pointer
    fun object_getInstanceVariable(obj: Pointer, name: String, outValue: Pointer): Pointer
    fun object_getIvar(`object`: Pointer, ivar: Pointer): Pointer
    fun object_setClass(`object`: Pointer, cls: Pointer): Pointer
    fun object_setInstanceVariable(obj: Pointer, name: String, value: Pointer): Pointer
    fun object_setIvar(`object`: Pointer, ivar: Pointer, value: Pointer)
    fun property_getAttributes(property: Pointer): String
    fun protocol_conformsToProtocol(proto: Pointer, other: Pointer): Boolean
    fun protocol_copyMethodDescriptionList(protocol: Pointer, isRequiredMethod: Boolean, isInstanceMethod: Boolean, outCount: Pointer): Structure
    fun protocol_copyPropertyList(proto: Pointer, outCount: Pointer): Pointer
    fun protocol_copyProtocolList(proto: Pointer, outCount: Pointer): Pointer
    fun protocol_getMethodDescription(proto: Pointer, aSel: Pointer, isRequiredMethod: Boolean, isInstanceMethod: Boolean): Pointer
    fun protocol_getName(proto: Pointer): String
    fun protocol_getProperty(proto: Pointer, name: String, isRequiredProperty: Boolean, isInstanceProperty: Boolean): Pointer
    fun protocol_isEqual(protocol: Pointer, other: Pointer): Boolean
    fun sel_getName(aSelector: Pointer): String

    fun sel_getUid(name: String): Pointer
    fun sel_isEqual(lhs: Pointer, rhs: Pointer): Boolean
    fun sel_registerName(name: String): Pointer
}

val ObjectiveC = Native.loadLibrary("objc.A", ObjectiveCRuntime::class.java) as ObjectiveCRuntime