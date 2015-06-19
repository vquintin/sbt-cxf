package io.teamscala.sbt.cxf

import java.io.File
import java.net.MalformedURLException

import sbt.Keys._
import sbt._
import sbt.classpath.ClasspathUtilities

import scala.util.Try

object Import {

  val cxf = config("cxf")

  object CxfKeys {

    val wsdl2java = taskKey[Seq[File]]("Generates java files from wsdls")
    val wsdls     = settingKey[Seq[Wsdl]]("wsdls to generate java files from")

  }

  case class Wsdl(key: String, uri: String, args: Seq[String] = Nil) {
    def outputDirectory(basedir: File) = new File(basedir, key).getAbsoluteFile
  }

}

object SbtCxf extends AutoPlugin {

  override def requires = sbt.plugins.JvmPlugin
  override def trigger = AllRequirements

  val autoImport = Import

  import autoImport._
  import CxfKeys._

  override def projectSettings: Seq[Setting[_]] = Seq(
    ivyConfigurations += cxf,
    version in cxf := "2.7.16",
    libraryDependencies <++= (version in cxf)(version => Seq(
      "org.apache.cxf" % "cxf-tools-wsdlto-core" % version % cxf,
      "org.apache.cxf" % "cxf-tools-wsdlto-databinding-jaxb" % version % cxf,
      "org.apache.cxf" % "cxf-tools-wsdlto-frontend-jaxws" % version % cxf
    )),
    wsdls in cxf := Nil,
    managedClasspath in cxf <<= (classpathTypes in cxf, update) map { (ct, report) =>
      Classpaths.managedJars(cxf, ct, report)
    },
    sourceManaged in cxf <<= sourceManaged(_ / "cxf"),
    managedSourceDirectories in Compile <++= (wsdls in cxf, sourceManaged in cxf) { (wsdls, basedir) =>
      wsdls.map(_.outputDirectory(basedir))
    },
    wsdl2java in cxf <<= (wsdls in cxf, sourceManaged in cxf, managedClasspath in cxf).map { (wsdls, basedir, cp) =>
      val classpath = cp.files
      (for (wsdl <- wsdls) yield {
        val output = wsdl.outputDirectory(basedir)
        val maybeFile = Try(url(wsdl.uri)).map(IO.urlAsFile).recover {
          case e: MalformedURLException => None
        }.get
        if (!maybeFile.exists(_.lastModified() <= output.lastModified())) {
          val args = Seq("-d", output.getAbsolutePath) ++ wsdl.args :+ wsdl.uri
          callWsdl2java(wsdl.key, output, args, classpath)
        }
        (output ** "*.java").get
      }).flatten
    },
    sourceGenerators in Compile <+= wsdl2java in cxf
  )

  private def callWsdl2java(id: String, output: File, args: Seq[String], classpath: Seq[File]) {
    // TODO: Use the built-in logging mechanism from SBT when I figure out how that work - trygve
    println("WSDL: id=" + id + ", args=" + args)
    println("Removing output directory...")
    IO.delete(output)

    println("Compiling WSDL...")
    val start = System.currentTimeMillis()
    val classLoader = ClasspathUtilities.toLoader(classpath)
    val WSDLToJava = classLoader.loadClass("org.apache.cxf.tools.wsdlto.WSDLToJava")
    val ToolContext = classLoader.loadClass("org.apache.cxf.tools.common.ToolContext")
    val constructor = WSDLToJava.getConstructor(classOf[Array[String]])
    val run = WSDLToJava.getMethod("run", ToolContext)
    val oldContextClassLoader = Thread.currentThread.getContextClassLoader
    try {
      // to satisfy the jaxb reflection madness classLoader requirements
      Thread.currentThread.setContextClassLoader(classLoader)
      val instance = constructor.newInstance(args.toArray)
      run.invoke(instance, ToolContext.newInstance().asInstanceOf[AnyRef])
    } catch {
      case e: Throwable =>
        // TODO: Figure out if there is a better way to signal errors to SBT.
        // Some of the CXF exceptions contain output that's proper to show to
        // the user as it explains the error that occurred.
        e.printStackTrace()
        throw e
    } finally {
      val end = System.currentTimeMillis()
      println("Compiled WSDL in " + (end - start) + "ms.")
      Thread.currentThread.setContextClassLoader(oldContextClassLoader)
    }
  }

}