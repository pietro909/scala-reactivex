import org.jsoup.Jsoup
import scala.collection.JavaConverters._

package object downloader {

  def findLinks(body: String, url: String): Iterator[String] = {
    val document = Jsoup.parse(body, url)
    val links = document.select("a[href]")
    for {
      link <- links.iterator().asScala
      // Returns only `http*` links to avoid `mailto` and `ws*`
      if link.absUrl("href").toString.startsWith("http")
    } yield link.absUrl("href")
  }

}
