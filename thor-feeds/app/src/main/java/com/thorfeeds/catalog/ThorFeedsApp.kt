package com.thorfeeds.catalog

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.thorfeeds.catalog.data.CatalogDatabase
import com.thorfeeds.catalog.data.LibraryRepository
import com.thorfeeds.catalog.data.SettingsStore
import com.thorfeeds.catalog.download.DownloadController
import com.thorfeeds.catalog.net.OkHttpFileFetcher
import com.thorfeeds.catalog.net.RealMirrorRouter
import com.thorfeeds.catalog.net.ReleaseDownloader
import com.thorfeeds.catalog.data.SteamArtRepository
import com.thorfeeds.catalog.net.HttpIdentity
import com.thorfeeds.catalog.webview.WebFeedGate
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()
    val webFeeds = WebFeedGate()
    val database: CatalogDatabase = Room.databaseBuilder(
        appContext,
        CatalogDatabase::class.java,
        "catalog.db",
    ).fallbackToDestructiveMigration().build()
    val library = LibraryRepository(
        context = appContext,
        settings = SettingsStore(appContext),
        dao = database.releases(),
        http = http,
        webFeeds = webFeeds,
    )
    val steamArt = SteamArtRepository(appContext.filesDir, http)
    val imageHttp: OkHttpClient = http.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", HttpIdentity.FEED_BROWSER_USER_AGENT)
                    .build(),
            )
        }
        .build()
    val downloads = DownloadController(
        context = appContext,
        library = library,
        downloader = ReleaseDownloader(RealMirrorRouter.create(appContext, http), OkHttpFileFetcher(http)),
    )
}

class ThorFeedsApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override fun newImageLoader(): ImageLoader {
        val builder = ImageLoader.Builder(this).crossfade(true)
        if (::graph.isInitialized) builder.okHttpClient(graph.imageHttp)
        return builder.build()
    }

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as ThorFeedsApp).graph
    }
}
