package com.tirelog.app.navi

import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.tirelog.core.model.SavedShop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 타이어점 길안내. 위치를 쓰지 않는다.
 * - '내 타이어점'이 있으면 그 좌표로 경로를 요청한다(requestRoute).
 * - 없으면 내비의 장소 검색 화면을 "타이어"로 연다(requestNavigationScreen + SearchPlace). 주변 검색은 내비가 한다.
 */
class TireShopNavigator(context: Context) {
    private val appContext = context.applicationContext
    private var navi: NaviHelper? = null
    private var ready = false
    private var recent: CompletableDeferred<List<RecentDestinationInfo>>? = null
    private var bookmarks: CompletableDeferred<List<BookmarkInfo>>? = null

    private val listener = object : NaviHelperEventListener {
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
            recent?.complete(recentDestinationInfoList)
        }

        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            bookmarks?.complete(bookmarkInfoList)
        }
    }

    private suspend fun connect(): NaviHelper? {
        navi?.takeIf { ready }?.let { return it }
        return try {
            val helper = navi ?: NaviHelper(appContext).also {
                navi = it
                it.addListener(listener)
            }
            val done = CompletableDeferred<Boolean>()
            helper.initialize({ done.complete(true) }, { error ->
                Log.w(TAG, "NaviHelper 초기화 실패", error)
                done.complete(false)
            })
            ready = withTimeoutOrNull(3_000) { done.await() } == true
            if (ready) helper else null
        } catch (t: Throwable) {
            Log.w(TAG, "NaviHelper 사용 불가", t)
            null
        }
    }

    /** 성공하면 true. 내비가 없으면 false를 돌려주고 화면에서 안내한다. */
    suspend fun navigateToTireShop(shop: SavedShop?): Boolean {
        val helper = connect() ?: return false
        return runCatching {
            if (shop != null) {
                helper.requestRoute(
                    RouteInfo(
                        latitude = shop.latitude,
                        longitude = shop.longitude,
                        displayLatitude = null,
                        displayLongitude = null,
                        poiName = shop.name,
                        poiId = shop.poiId ?: "",
                        poiSubId = shop.poiSubId ?: "0",
                        address = shop.address ?: "",
                    ),
                )
            } else {
                helper.requestNavigationScreen(NavigationScreenRequest.SearchPlace(SEARCH_KEYWORD))
            }
        }.onFailure { Log.w(TAG, "길안내 요청 실패", it) }.isSuccess
    }

    /** 내비 최근 목적지·즐겨찾기 중 '내 타이어점' 후보. 이름에 타이어가 들어간 곳을 앞에 둔다. */
    suspend fun shopCandidates(): List<SavedShop>? {
        val helper = connect() ?: return null
        recent = CompletableDeferred()
        bookmarks = CompletableDeferred()
        runCatching { helper.getRecentDestinationInfo() }
        runCatching { helper.getBookmarkInfo() }
        val recentList = withTimeoutOrNull(3_000) { recent?.await() }.orEmpty()
        val bookmarkList = withTimeoutOrNull(1_000) { bookmarks?.await() }.orEmpty()
        val shops = bookmarkList.map { SavedShop(it.name ?: "즐겨찾기", it.latitude, it.longitude, it.poiId, it.poiSubId, it.address) } +
            recentList.map { SavedShop(it.name ?: "최근 목적지", it.latitude, it.longitude, it.poiId, it.poiSubId, it.address) }
        return shops
            .distinctBy { "${it.name}|${it.latitude}|${it.longitude}" }
            .sortedByDescending { it.name.contains("타이어") || it.name.contains("tire", ignoreCase = true) }
    }

    fun release() {
        runCatching { navi?.removeListener(listener) }
        runCatching { navi?.release() }
        navi = null
        ready = false
    }

    private companion object {
        const val TAG = "TireShopNavigator"
        const val SEARCH_KEYWORD = "타이어"
    }
}
