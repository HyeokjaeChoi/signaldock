package local.signaldock.consumer

import android.app.Application

/** Baseline for the host-cost measurement (issue 36, ADR-0020): the same app without the SDK. */
class ConsumerApplication : Application()
