package it.mdv.dttchannels

import android.content.Context
import android.media.tv.TvView

/**
 * TvView that always keeps its video surface at the full view size.
 *
 * The Technicolor DtvInputService (TIM Vision has the same bug) calls Session.layoutSurface(0, 0, 1919, 1080)
 * on the first picture of a session. TvView then shrinks its SurfaceView to 1919 px, the display scales
 * the 1920 px HD picture into it and it looks soft ("SD"). Later tunes request 1920 and look sharp.
 * Ignoring the requested layout keeps the surface at 1920x1080 on every tune, cold start or reopen.
 */
class FullScreenTvView(context: Context) : TvView(context) {
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        for (i in 0 until childCount) getChildAt(i).layout(0, 0, right - left, bottom - top)
    }
}
