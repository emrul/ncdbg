package com.programmaticallyspeaking.ncd.chrome.domains

import com.programmaticallyspeaking.ncd.chrome.domains.Runtime.{ObjectPreview, PropertyPreview, RemoteObject}
import com.programmaticallyspeaking.ncd.host._
import com.programmaticallyspeaking.ncd.host.types.{ObjectPropertyDescriptor, PropertyDescriptorType, Undefined}
import com.programmaticallyspeaking.ncd.testing.UnitTest
import org.scalatest.prop.TableDrivenPropertyChecks

class PreviewGeneratorTest extends UnitTest with TableDrivenPropertyChecks {
  // These are set low for testing
  val maxStringLength = 10
  val maxProperties = 2
  val maxIndices = 3

  def objectIdString(id: String) = ObjectId(id).toString

  def valueDescriptor(value: Any, isOwn: Boolean = true) =
    ObjectPropertyDescriptor(PropertyDescriptorType.Data, false, true, true, isOwn = isOwn, Some(value match {
      case node: ValueNode => node
      case other if other == null => EmptyNode
      case other => SimpleValue(other)
    }), None, None)

  val aFunction = FunctionNode("fun", "function fun() {}", ObjectId("fun"))

  val accessorDescriptor =
    ObjectPropertyDescriptor(PropertyDescriptorType.Accessor, false, true, true, true, None, Some(aFunction), None)

  val propertyMaps: Map[String, Map[String, ObjectPropertyDescriptor]] = Map(
    objectIdString("null") -> Map("foo" -> valueDescriptor(null)),
    objectIdString("prim") -> Map("foo" -> valueDescriptor(42)),
    objectIdString("string") -> Map("foo" -> valueDescriptor("abcdefghij")),
    objectIdString("longstring") -> Map("foo" -> valueDescriptor("abcdefghijk")),
    objectIdString("toomanyprops") -> Map("foo" -> valueDescriptor(42), "bar" -> valueDescriptor(43), "baz" -> valueDescriptor(44)),
    objectIdString("toomanyindices") -> Map("0" -> valueDescriptor(42), "1" -> valueDescriptor(43), "2" -> valueDescriptor(44), "3" -> valueDescriptor(45)),
    objectIdString("withprotoprop") -> Map("foo" -> valueDescriptor(42), "bar" -> valueDescriptor(43, isOwn = false)),
    objectIdString("array2") -> Map("0" -> valueDescriptor(42), "1" -> valueDescriptor(43), "length" -> valueDescriptor(2)),
    objectIdString("withpropnamedunderscoreproto") -> Map("__proto__" -> valueDescriptor("dummy")),
    objectIdString("arrayofobject") -> Map("0" -> valueDescriptor(ObjectNode("Object", ObjectId("obj")))),
    objectIdString("arrayoffunction") -> Map("0" -> valueDescriptor(aFunction)),
    objectIdString("arrayofundefined") -> Map("0" -> valueDescriptor(SimpleValue(Undefined))),
    objectIdString("objwithfunctionvalue") -> Map("foo" -> valueDescriptor(aFunction)),
    objectIdString("withcomputedprop") -> Map("foo" -> accessorDescriptor),
    objectIdString("objwithdate") -> Map("foo" -> valueDescriptor(DateNode("Sat Jan 28 2017 13:25:02 GMT+0100 (W. Europe Standard Time)", ObjectId("date")))),
    objectIdString("objwithregexp") -> Map("foo" -> valueDescriptor(RegExpNode("/[a-z0-9A-Z_]{3,5}.*[a-z]$/", ObjectId("regexp"))))
  )

  def previewWithProperties(propertyPreview: PropertyPreview*) =
    ObjectPreview("object", "Object", false, None, Seq(propertyPreview: _*))

  def arrayPreviewWithProperties(propertyPreview: PropertyPreview*) = {
    val length = propertyPreview.size
    ObjectPreview("object", s"Array[$length]", false, Some("array"), Seq(propertyPreview: _*))
  }

  // TODO: What's the description for a typed array? Investigate!
//  def typedarrayPreviewWithProperties(propertyPreview: PropertyPreview*) = {
//    val length = propertyPreview.size
//    ObjectPreview("object", s"Array[$length]", false, Some("typedarray"), Seq(propertyPreview: _*))
//  }

  def getPreview(obj: RemoteObject, props: Seq[(String, ObjectPropertyDescriptor)]): Option[ObjectPreview] = {
    val generator = new PreviewGenerator(_ => props, PreviewGenerator.Options(maxStringLength, maxProperties, maxIndices))
    generator.withPreviewForObject(obj).preview
  }

  def forObject(objectId: String) = RemoteObject.forObject("Object", objectId)

  val testCases = Table(
    ("description", "object", "expected"),

    ("ignores non-object", RemoteObject.forNumber(42), None),

    ("handles null property",
      forObject(objectIdString("null")),
      Some(previewWithProperties(PropertyPreview("foo", "object", "null", Some("null"))))),

    ("handles primitive property",
      forObject(objectIdString("prim")),
      Some(previewWithProperties(PropertyPreview("foo", "number", "42", None)))),

    ("handles a string property with a string not exceeding the max length",
      forObject(objectIdString("string")),
      Some(previewWithProperties(PropertyPreview("foo", "string", "abcdefghij", None)))),

    ("abbreviates a long string",
      forObject(objectIdString("longstring")),
      Some(previewWithProperties(PropertyPreview("foo", "string", "abcdefghij\u2026", None)))),

    ("ignores prototype properties",
      forObject(objectIdString("withprotoprop")),
      Some(previewWithProperties(PropertyPreview("foo", "number", "42", None)))),

    ("ignores computed properties",
      forObject(objectIdString("withcomputedprop")),
      Some(previewWithProperties())),

    ("ignores property '__proto__'",
      forObject(objectIdString("withpropnamedunderscoreproto")),
      Some(previewWithProperties())),

    ("ignores 'length' property of an array",
      RemoteObject.forArray(2, None, objectIdString("array2")),
      Some(arrayPreviewWithProperties(PropertyPreview("0", "number", "42", None), PropertyPreview("1", "number", "43", None)))),

    ("handles array of objects with description",
      RemoteObject.forArray(1, None, objectIdString("arrayofobject")),
      Some(arrayPreviewWithProperties(PropertyPreview("0", "object", "Object", None)))),

    ("handles array of functions with empty value for a function",
      RemoteObject.forArray(1, None, objectIdString("arrayoffunction")),
      Some(arrayPreviewWithProperties(PropertyPreview("0", "function", "", None)))),

    ("handles array of undefined",
      RemoteObject.forArray(1, None, objectIdString("arrayofundefined")),
      Some(arrayPreviewWithProperties(PropertyPreview("0", "undefined", "undefined", None)))),

    ("ignores an object property with a function value",
      forObject(objectIdString("objwithfunctionvalue")),
      Some(previewWithProperties())),

    ("abbreviates a Date string representation",
      forObject(objectIdString("objwithdate")),
      Some(previewWithProperties(PropertyPreview("foo", "object", "Sat Jan 28\u2026", Some("date"))))),

    ("abbreviates a RegExp string representation _in the middle_",
      forObject(objectIdString("objwithregexp")),
      Some(previewWithProperties(PropertyPreview("foo", "object", "/[a-z\u2026-z]$/", Some("regexp"))))),

    ("ignores a null object",
      RemoteObject.nullValue,
      None),

    ("ignores a non-object such as a function",
      RemoteObject.forFunction("fun", "function fun() {}", objectIdString("fun")),
      None)
  )

  "Preview generation" - {
    forAll(testCases) { (desc, obj, expected) =>
      desc in {
        val props = obj.objectId.flatMap(propertyMaps.get).map(_.toSeq).getOrElse(Seq.empty)
        getPreview(obj, props) should be (expected)
      }
    }

    "with property count over the max count" - {
      def preview = {
        val objId = objectIdString("toomanyprops")
        val obj = forObject(objId)
        val props = propertyMaps.getOrElse(objId, Map.empty).toSeq
        getPreview(obj, props)
      }

      "ignores surplus properties" in {
        preview.map(_.properties.map(_.name)) should be (Some(Seq("foo", "bar")))
      }

      "indicates overflow" in {
        preview.map(_.overflow) should be (Some(true))
      }
    }

    "with index count over the max count" - {
      def preview = {
        val objId = objectIdString("toomanyindices")
        val props = propertyMaps.getOrElse(objId, Map.empty).toSeq
        val obj = RemoteObject.forArray(props.size, None, objId)
        getPreview(obj, props)
      }

      "ignores surplus indices" in {
        preview.map(_.properties.map(_.name)) should be (Some(Seq("0", "1", "2")))
      }

      "indicates overflow" in {
        preview.map(_.overflow) should be (Some(true))
      }
    }

    "of a scope object" - {
      def preview(scopeType: String, name: String) = {
        val objId = objectIdString("obj-1")
        val obj = RemoteObject.forScope(scopeType, name, objId)
        getPreview(obj, Seq.empty)
      }

      def findProp(preview: Option[ObjectPreview], name: String) =
        preview.flatMap(_.properties.find(_.name == name)).getOrElse(throw new IllegalArgumentException("No property: " + name))

      "returns a property for the name" in {
        findProp(preview("closure", "test"), "name").value should be ("test")
      }

      "returns a property for the type" in {
        findProp(preview("closure", "test"), "type").value should be ("closure")
      }

      "returns a property for the object" in {
        findProp(preview("closure", "test"), "object").value should be ("Object")
      }

      "returns 3 properties" in {
        val p = preview("closure", "test")
        p.map(_.properties.size) should be (Some(3))
      }
    }
  }
}
