package com.ai.playtv.di

import android.content.Context
import com.ai.playtv.model.repository.sample.SampleRepository


/**
 * 进程组合根，仅保存 applicationContext 和已注册依赖；不持有页面、Binding 或 ViewModel。
 * 页面通过自己的 Factory 取得依赖。会话/任务资源由对应子容器拥有，不能加入此容器永久持有。
 */
class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext


    /** 无启动副作用的本地样例；同一进程复用，正式业务按职责增加明确属性。 */
    val sampleRepository: SampleRepository by lazy { SampleRepository() }
}
