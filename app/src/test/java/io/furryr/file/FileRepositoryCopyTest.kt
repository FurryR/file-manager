package io.furryr.file

import io.furryr.file.copy.CopyProgress
import io.furryr.file.copy.FileSource
import io.furryr.file.copy.OpenMode
import io.furryr.file.copy.StreamFileSource
import io.furryr.file.model.FileEntry
import io.furryr.file.provider.FileProvider
import io.furryr.file.provider.ProviderFeature
import io.furryr.file.provider.ProviderStat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class FileRepositoryCopyTest {

    @Test
    fun `move preserves source when cross-provider copy fails`() {
        val sourceProvider = SourceProvider()
        val destinationProvider = FailingDestinationProvider()
        val sourceScheme = "copy-source-${UUID.randomUUID()}"
        val destinationScheme = "copy-destination-${UUID.randomUUID()}"
        FileRepository.register(sourceScheme, sourceProvider)
        FileRepository.register(destinationScheme, destinationProvider)

        val failure = runCatching {
            runBlocking {
                FileRepository.move("$sourceScheme:///file.txt", "$destinationScheme:///").collect { }
            }
        }.exceptionOrNull()

        assertNotNull("copy failure must propagate", failure)
        assertFalse(sourceProvider.deleted)
    }

    private class SourceProvider : TestProvider("source") {
        var deleted = false

        override suspend fun stat(path: String): Result<ProviderStat> =
            Result.success(ProviderStat(exists = true, isDirectory = false))

        override suspend fun open(path: String, mode: OpenMode): Result<FileSource> =
            Result.success(StreamFileSource(ByteArrayInputStream("content".toByteArray()), size = 7))

        override suspend fun delete(path: String): Result<Unit> {
            deleted = true
            return Result.success(Unit)
        }
    }

    private class FailingDestinationProvider : TestProvider("destination") {
        override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> = flow {
            throw IOException("destination write failed")
        }
    }

    private abstract class TestProvider(override val scheme: String) : FileProvider {
        override val authority: String? = null
        override val label: String = scheme
        override val features: Set<ProviderFeature> = emptySet()

        override suspend fun list(path: String): Result<List<FileEntry>> = Result.success(emptyList())
        override suspend fun stat(path: String): Result<ProviderStat> = Result.success(ProviderStat())
        override suspend fun createFile(path: String): Result<Unit> = Result.success(Unit)
        override suspend fun createDirectory(path: String): Result<Unit> = Result.success(Unit)
        override suspend fun delete(path: String): Result<Unit> = Result.success(Unit)
        override suspend fun rename(path: String, newName: String): Result<Unit> = Result.success(Unit)
        override suspend fun open(path: String, mode: OpenMode): Result<FileSource> =
            Result.failure(UnsupportedOperationException())
        override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> = flow { }
        override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> = flow { }
        override fun isAvailable(): Boolean = true
        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
    }
}
