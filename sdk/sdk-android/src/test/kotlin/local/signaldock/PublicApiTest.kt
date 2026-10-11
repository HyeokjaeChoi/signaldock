package local.signaldock

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.visibility

/** Transport DTOs must not be part of the SDK's public API (architecture "Components"; spec section 3). */
class PublicApiTest {
    private fun classNames(location: File): List<String> {
        val entries = if (location.isDirectory) {
            location.walkTopDown().filter { it.isFile }.map { it.relativeTo(location).path.replace(File.separatorChar, '/') }.toList()
        } else {
            ZipFile(location).use { z -> z.entries().asSequence().map { it.name }.toList() }
        }
        return entries.filter { it.startsWith("org/openapitools/") && it.endsWith(".class") && '$' !in it }
            .map { it.removeSuffix(".class").replace('/', '.') }
    }

    @Test
    fun `no top-level class in org_openapitools is public`() {
        val anchor = Class.forName("org.openapitools.client.models.EventIngest")
        val location = File(anchor.protectionDomain!!.codeSource!!.location.toURI())
        val names = classNames(location)
        assertTrue("no generated classes found in $location", names.size >= 6)
        for (name in names) {
            val meta = Class.forName(name).getAnnotation(Metadata::class.java)!!
            val km = KotlinClassMetadata.readLenient(meta) as KotlinClassMetadata.Class
            assertTrue("$name must be internal, was ${km.kmClass.visibility}", km.kmClass.visibility == Visibility.INTERNAL)
        }
    }
}
