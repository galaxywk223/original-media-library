package com.galaxywk.originalmedialibrary.android

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFilterTest {
    private fun item(id: String, title: String, kind: String, trashed: Long? = null) = CollectionWithAssets(
        CollectionEntity(id, null, null, title, "作者", kind, trashedAt = trashed),
        listOf(AssetEntity("asset-$id", id, "/media/$title.$kind", "$title.$kind", kind, "$kind/*", 1, 1)),
    )

    @Test fun filtersSearchKindAndTrash() {
        val values = listOf(item("1", "猫", "video"), item("2", "狗", "audio"), item("3", "猫旧", "video", 1))
        assertEquals(listOf("1"), LibraryFilter.apply(values, LibraryQuery("猫", "video")).map { it.collection.id })
        assertEquals(listOf("2"), LibraryFilter.apply(values, LibraryQuery(kind = "audio")).map { it.collection.id })
        assertEquals(listOf("3"), LibraryFilter.apply(values, LibraryQuery("", "all", trash = true)).map { it.collection.id })
    }
}
