package com.programmaticallyspeaking.ncd.infra

import java.net.URL

import com.programmaticallyspeaking.ncd.testing.UnitTest
import org.scalatest.prop.TableDrivenPropertyChecks

class ScriptURLTest extends UnitTest with TableDrivenPropertyChecks {

  val fromPathCases =
    Table(
      ("desc", "input", "output"),
      ("Windows path", "c:\\temp\\test.txt","file:///c:/temp/test.txt"),
      ("Path with ..", "c:\\temp\\subdir\\..\\test.txt","file:///c:/temp/test.txt"),
      ("Unix path", "/tmp/test.txt", "file:///tmp/test.txt"),
      ("Windows path on Unix form", "/c:/tmp/test.txt", "file:///c:/tmp/test.txt"),
      ("URL-like non-file path", "eval:/foo/bar", "eval:///foo/bar"),
      ("file URL without authority", "file:/foo/bar", "file:///foo/bar"),
      ("file URL without authority and ..", "file:/foo/subdir/../bar", "file:///foo/bar"),
      ("file URL with authority", "file:///foo/bar", "file:///foo/bar"),
      ("data URL", "data:application/json;base64,e30=", "data:application/json;base64,e30="),
      ("HTTP URL", "http://localhost/test", "http://localhost/test")
    )

  val resolveCases =
    Table(
      ("desc", "original", "input", "output"),
      ("relative Windows path", "c:\\temp\\test.txt", "bar.txt", "c:\\temp\\bar.txt"),
      ("absolute Windows path", "c:\\temp\\test.txt", "c:\\files\\data.txt", "c:\\files\\data.txt"),
      ("absolute Unix path", "/tmp/test.txt", "/files/data.txt", "/files/data.txt"),
      ("relative URL", "http://localhost/test.txt", "bar.txt", "http://localhost/bar.txt"),
      ("relative path with ..", "/tmp/foo/bar.txt", "../baz/qux.txt", "file:///tmp/baz/qux.txt")
    )

  "create" - {
    forAll(fromPathCases) { (desc, input, output) =>
      s"handles $desc" in {
        val sut = ScriptURL.create(input)
        sut.toString should be (output)
      }
    }

    "accepts an URL" in {
      val url = new URL("http://localhost/test.txt")
      val sut = ScriptURL.create(url)
      sut.toString should be ("http://localhost/test.txt")
    }

    "rejects a relative file path" in {
      assertThrows[IllegalArgumentException](ScriptURL.create("im/relative"))
    }

    "rejects a relative file path with .." in {
      assertThrows[IllegalArgumentException](ScriptURL.create("../im/relative"))
    }
  }

  "resolve" - {

    forAll(resolveCases) { (desc, original, input, output) =>
      s"handles $desc" in {
        val sut = ScriptURL.create(original)
        val sut2 = sut.resolve(input)
        sut2 should be (ScriptURL.create(output))
      }
    }
  }

  "toFile" - {
    "returns a file with an appropriate path" in {
      val original = "c:\\temp\\test.txt"
      val sut = ScriptURL.create(original)
      val f = sut.toFile

      // Cannot test path accurately on both Windows and Unix, so do a round-trip.
      val sut2 = ScriptURL.create(f.getAbsolutePath)
      sut2.toString should be ("file:///c:/temp/test.txt")
    }
  }

  "isFile" - {
    "returns true for a file URL" in {
      val sut = ScriptURL.create("/tmp/test.txt")
      sut.isFile should be (true)
    }

    "returns false for a non-file URL" in {
      val sut = ScriptURL.create("http://localhost/test.txt")
      sut.isFile should be (false)
    }
  }
}
