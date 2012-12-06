package model

import akka.actor.Actor
import play.api._
import com.codeminders.s3simpleclient._
import com.codeminders.s3simpleclient.model._
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipOutputStream
import org.apache.commons.io.output.ByteArrayOutputStream
import java.util.zip.ZipEntry
import play.api.templates.Html
import org.apache.commons.io.FileUtils
import scala.util.matching.Regex
import com.codeminders.s3simpleclient._

class S3Indexer(indexerId: String) extends Actor {

  val INDEXFILE = "index.html"

  Logger.debug("%s:%s started.".format(this.getClass().getName(), indexerId))

  def receive = {
    case "newTask" =>
      sender ! "getTask"
    case t: S3IndexTask =>
      try {
        Logger.debug("%s:%s: processing %s.".format(this.getClass().getName(), indexerId, t.id))
        if (t.properties.get() == None) {
          Logger.info("Got an empty task with id %s.".format(t.id))
          t.updateStatus(TaskStatus.error("Please set bucket name"))
        } else {
          val properties = t.properties.get().get
          if (properties.name.isEmpty()) {
            t.updateStatus(TaskStatus.error("Please set bucket name"))
            Logger.info("Won't process - empty bucket name")
          } else {
            val s3 = properties.outputOption match {
              case OutputOption.ZipArchive => SimpleS3()
              case OutputOption.Bucket =>
                properties.credentials match {
                  case None => throw new Exception("Won't process - empty credentials")
                  case Some(c) => SimpleS3(AWSCredentials(c.accessKeyId, c.secretKey))
                }
            }
            Logger.info("Started task %s, bucket %s. ".format(t.id, properties.name))
            if (properties.outputOption == OutputOption.Bucket && properties.credentials != None) {
              generateIndexToBucket(t.id, s3, properties, t.updateStatus(_), t.storeResult(_))
            } else {
              generateIndexToArchive(t.id, s3, properties, t.updateStatus(_), t.storeResult(_))
            }
            Logger.info("Finished task %s, bucket %s. ".format(t.id, properties.name))
          }
        }
      } catch {
        case e: AmazonServiceException => {
          Logger.warn(e.getMessage(), e)
          t.updateStatus(TaskStatus.error("%s: %s".format(e.errorCode, e.message)))
        }
        case e: Exception => {
          Logger.error(e.getMessage(), e)
          t.updateStatus(TaskStatus.error("The server encountered an internal error. Please try again later."))
        }
      }
  }

  def generateIndex(root: KeysTree, outputFunction: (String, Array[Byte]) => Unit, status: (TaskStatus) => Unit, properties: Properties): Unit = {
    def generateIndex(root: KeysTree, outputFunction: (String, Array[Byte]) => Unit, status: (TaskStatus) => Unit, template: (String, Seq[Seq[Html]]) => Html, include: (String) => Boolean, keysFormatter: (Option[(KeysTree, Key)]) => List[Html], objectsDone: Int, objectsLeft: Int): Unit = {
      val indexName = root.name + INDEXFILE

      val header = Array(keysFormatter(None))

      val parentLink = Array(List(Html("""<div class="back"><a href="..">..</a></div>""")))

      val directories = for (g <- root.keyGroups if (include(g.name))) yield {
        val name = g.name.substring(root.name.size)
        List(Html("""<div class="dir"><a href="%s">%s</a></div>""".format(name, name)))
      }

      val files = for (k <- root.keys if (include(k.name))) yield {
        keysFormatter(Option((root, k)))
      }

      val data = header ++ parentLink ++ directories ++ files

      val indexData = template(if (root.name.isEmpty()) "/" else root.name, data).toString
      val bytes = indexData.getBytes("UTF-8")

      outputFunction(indexName, bytes)

      val percents = ((objectsDone.toFloat / objectsLeft.toFloat) * 100).toInt

      status(TaskStatus.info(percents, "Processing keys with prefix %s".format(if (root.name.isEmpty()) "/" else root.name)))

      var counter = objectsDone
      for (g <- root.keyGroups) yield {
        generateIndex(g, outputFunction, status, template, include, keysFormatter, counter, objectsLeft * root.groupsNumber)
        counter += 1
      }
    }
    val cssStyleLinks = "/css/%s.css".format(properties.template.toString().toLowerCase()) :: properties.customCSS.toList
    val template = views.html.index(properties.title, properties.header, Html(properties.footer), cssStyleLinks)(_, _)
    val filter = keysFilter(properties.includedPaths.foldLeft(List[Regex]())((l, p) => Utils.globe2Regexp(p) :: l),
      properties.excludedPaths.foldLeft(List[Regex]())((l, p) => Utils.globe2Regexp(p) :: l))(_)
    generateIndex(root, outputFunction, status, template, filter, FileListFormat.toHtml(properties.fileListFormat)(_), 0, 1)
  }

  def generateIndexToArchive(taskId: String, s3: SimpleS3, properties: Properties, status: (TaskStatus) => Unit, storeResult: (Array[Byte]) => Unit) {
    val result = new ByteArrayOutputStream();
    val outputStream = new ZipOutputStream(result)
    val outputFunction = toArchive(outputStream)(_, _)
    Utils.copyStyleTo(properties.stylesLocation, properties.template.toString().toLowerCase(), outputFunction)
    generateIndex(s3.bucket(properties.name).list(), outputFunction, status, properties)
    outputStream.close()
    storeResult(result.toByteArray())
    status(TaskStatus.done("Done", taskId))
  }

  def generateIndexToBucket(taskId: String, s3: SimpleS3, properties: Properties, status: (TaskStatus) => Unit, storeResult: (Array[Byte]) => Unit) {
    val result = new ByteArrayOutputStream();
    val outputFunction = toBucket(s3, s3.bucket(properties.name))(_, _)
    Utils.copyStyleTo(properties.stylesLocation, properties.template.toString().toLowerCase(), outputFunction)
    generateIndex(s3.bucket(properties.name).list(), outputFunction, status, properties)
    status(TaskStatus.done("Done"))
  }

  def toArchive(output: ZipOutputStream)(keyName: String, data: Array[Byte]) {
    output.putNextEntry(new ZipEntry(keyName));
    output.write(data)
    output.closeEntry();
  }

  def toBucket(s3: SimpleS3, bucket: Bucket)(keyName: String, data: Array[Byte]) {
    val key = bucket.key(keyName).withACL("public-read").withContentType("text/html; charset=UTF-8")
    key <<< (new ByteArrayInputStream(data), data length)
  }

  def keysFilter(includedKeys: Seq[Regex], excludedKeys: Seq[Regex])(name: String): Boolean = {
    if (!includedKeys.isEmpty) {
      includedKeys.exists(i => i.pattern.matcher(name).matches())
    } else true && !excludedKeys.exists(e => e.pattern.matcher(name).matches())
  }

}