package com.wifiadb.ts

import android.app.Application
import com.wifiadb.ts.data.ConfigRepository

/**
 * Application 单例，持有全局唯一的配置仓库。
 *
 * 刻意不引入 DI 框架：整个 app 只有两个需要共享的对象
 * （配置仓库 + 一个 ViewModel），直接在这里持足以。
 */
class AdbApp : Application() {

    lateinit var configRepository: ConfigRepository
        private set

    override fun onCreate() {
        super.onCreate()
        configRepository = ConfigRepository(this)
    }
}
