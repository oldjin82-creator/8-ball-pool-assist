package com.example.poolvisionpro

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.*
import android.os.*
import android.view.*
import android.widget.*
import org.opencv.android.OpenCVLoader
import kotlin.math.*

class LiveService:Service(){
 private val main=Handler(Looper.getMainLooper())
 private val worker=HandlerThread("vision")
 private var projection:MediaProjection?=null
 private var reader:ImageReader?=null
 private var display:VirtualDisplay?=null
 private lateinit var wm:WindowManager
 private lateinit var drawing:Guide
 private var panel:LinearLayout?=null
 private lateinit var status:TextView
 private var screenW=0; private var screenH=0
 private var balls=listOf<Ball>();@Volatile private var table=RectF()
 private var cue:Ball?=null;private var target:Ball?=null;private var pocket:PointF?=null
 private var powerStart:PointF?=null;private var powerEnd:PointF?=null
 private var mode="";private var first:PointF?=null
 private var busy=false;private var lastFrame=0L;private var stable=0;@Volatile private var frozen=false
 private var enabled=false; private var cancelled=false
 private var plan:Plan?=null
 override fun onBind(i:Intent?)=null
 private fun say(s:String){status.text=s}
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  if(intent?.action=="STOP"){stopSelf();return START_NOT_STICKY}
  if(projection!=null)return START_NOT_STICKY
  val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
  nm.createNotificationChannel(NotificationChannel("capture","Screen capture",NotificationManager.IMPORTANCE_LOW))
  val stop=PendingIntent.getService(this,1,Intent(this,LiveService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
  val n=Notification.Builder(this,"capture").setContentTitle("Pool Vision: screen capture active").setContentText("Tap Stop to end capture and gestures").setSmallIcon(android.R.drawable.ic_menu_view).addAction(android.R.drawable.ic_delete,"Stop",stop).build()
  if(Build.VERSION.SDK_INT>=29)startForeground(10,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(10,n)
  try{
   if(!OpenCVLoader.initLocal())error("OpenCV load failed")
   wm=getSystemService(WINDOW_SERVICE) as WindowManager
   val metrics=android.util.DisplayMetrics();@Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
   screenW=metrics.widthPixels;screenH=metrics.heightPixels
   if(screenW<screenH)error("Rotate to landscape before starting capture")
   table=RectF(screenW*.195f,screenH*.24f,screenW*.805f,screenH*.90f)
   buildOverlay()
   @Suppress("DEPRECATION") val consent=intent?.getParcelableExtra<Intent>("consent") ?: error("Missing capture permission")
   projection=(getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(intent.getIntExtra("code",0),consent)
   worker.start()
   projection!!.registerCallback(object:MediaProjection.Callback(){
    override fun onStop(){stopSelf()}
    override fun onCapturedContentResize(w:Int,h:Int){if(w!=screenW || h!=screenH){Toast.makeText(this@LiveService,"Capture size changed. Restart using entire-screen capture in landscape.",Toast.LENGTH_LONG).show();stopSelf()}}
   },main)
   // Full-size capture; detection runs on a 1280px-wide image.
   reader=ImageReader.newInstance(screenW,screenH,PixelFormat.RGBA_8888,2)
   display=projection!!.createVirtualDisplay("PoolVision",screenW,screenH,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,null)
   reader!!.setOnImageAvailableListener({r->
    val img=r.acquireLatestImage() ?: return@setOnImageAvailableListener
    try{
     val now=SystemClock.elapsedRealtime()
     if(frozen || now-lastFrame<550)return@setOnImageAvailableListener
     lastFrame=now
     val plane=img.planes[0];val paddedWidth=plane.rowStride/plane.pixelStride
     val raw=Bitmap.createBitmap(paddedWidth,screenH,Bitmap.Config.ARGB_8888);raw.copyPixelsFromBuffer(plane.buffer)
     val crop=Bitmap.createBitmap(raw,0,0,screenW,screenH)
     val scale=min(1f,1280f/screenW)
     val bitmap=Bitmap.createScaledBitmap(crop,(screenW*scale).toInt(),(screenH*scale).toInt(),true)
     val area=RectF(table.left*scale,table.top*scale,table.right*scale,table.bottom*scale)
     val found=Vision.detect(bitmap,area).map{Ball(it.x/scale,it.y/scale,it.r/scale)}
     if(bitmap!==crop)bitmap.recycle();if(crop!==raw)crop.recycle();raw.recycle()
     main.post{if(!cancelled)update(found)}
    }catch(e:Exception){main.post{if(!cancelled)say("Detection error: ${e.javaClass.simpleName}")}}finally{img.close()}
   },Handler(worker.looper))
  }catch(e:Exception){Toast.makeText(this,"Capture failed: ${e.message}",Toast.LENGTH_LONG).show();stopSelf()}
  return START_NOT_STICKY
 }
 private fun update(found:List<Ball>){
  fun track(old:Ball?):Ball? {if(old==null)return null;return found.minByOrNull{Vision.distance(it,old)}?.takeIf{Vision.distance(it,old)<old.r*1.5f}}
  val quiet=balls.size==found.size && found.isNotEmpty() && found.all{b->balls.any{Vision.distance(it,b)<b.r*.25f}}
  stable=if(quiet)stable+1 else 0
  cue=track(cue);target=track(target);balls=found
  plan=if(cue!=null && target!=null && pocket!=null)Vision.plan(cue!!,target!!,pocket!!,balls,table) else null
  if(mode.isEmpty() && !busy)say("${balls.size} circles • ${if(stable>=3)"stable" else "moving"} • ${if(plan!=null)"path ready" else "select cue, target, pocket / path blocked"}")
  drawing.invalidate()
 }
 private fun fullParams(touch:Boolean)=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or (if(touch)0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;alpha=.65f}
 private fun pick(s:String){if(busy)return;mode=s;first=null;wm.updateViewLayout(drawing,fullParams(true));say("Tap $s${if(s=="table" || s=="power")": first point, then second point" else ""}")}
 private fun finishPick(){mode="";wm.updateViewLayout(drawing,fullParams(false));stable=0;plan=null;drawing.invalidate()}
 private fun buildOverlay(){
  drawing=Guide();wm.addView(drawing,fullParams(false))
  val box=LinearLayout(this).apply{orientation=1;setPadding(8,4,8,4);setBackgroundColor(Color.rgb(12,22,38))}
  status=TextView(this).apply{setTextColor(Color.WHITE);textSize=11f;text="Calibrate table, cue, target and pocket"};box.addView(status)
  val row=LinearLayout(this);box.addView(row)
  fun button(s:String,fn:()->Unit){row.addView(Button(this).apply{text=s;textSize=10f;minimumWidth=0;setPadding(8,0,8,0);setOnClickListener{fn()}})}
  button("Table"){pick("table")};button("Cue"){pick("cue")};button("Target"){pick("target")};button("Pocket"){pick("pocket")};button("Power"){pick("power")}
  val row2=LinearLayout(this);box.addView(row2)
  val arm=Switch(this).apply{text="Tap-to-Aim enabled in game";textSize=10f;setTextColor(Color.WHITE);setOnCheckedChangeListener{_,v->enabled=v}}
  row2.addView(arm)
  row2.addView(Button(this).apply{text="Aim + Shoot";textSize=10f;setOnClickListener{execute()}})
  row2.addView(Button(this).apply{text="STOP";textSize=10f;setOnClickListener{cancelled=true;stopSelf()}})
  panel=box
  wm.addView(box,WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=0;y=0})
 }
 private fun execute(){
  val p=plan;val svc=GestureService.instance;val a=powerStart;val b=powerEnd
  if(busy)return
  if(!enabled || p==null || svc==null || !GestureService.inGame || stable<3 || a==null || b==null || SystemClock.elapsedRealtime()-lastFrame>1800){say("Need game foreground, gestures, stable clear path, Tap-to-Aim and power calibration");return}
  busy=true;frozen=true;say("Aiming — STOP cancels pending shot")
  // Hide drawings so they cannot obstruct the injected tap or contaminate screenshots.
  drawing.visibility=View.GONE
  val aim=Path().apply{moveTo(p.ghost.x,p.ghost.y)}
  svc.gesture(aim,100){ok->
   if(!ok){reset("Aim gesture failed");return@gesture}
   main.postDelayed({
    if(cancelled || !GestureService.inGame || !enabled){reset("Shot cancelled");return@postDelayed}
    val path=Path().apply{moveTo(a.x,a.y);lineTo(b.x,b.y)}
    svc.gesture(path,650){shot->reset(if(shot)"Gesture sent; verify result in game" else "Shot gesture failed")}
   },900)
  }
 }
 private fun reset(message:String){if(cancelled)return;busy=false;frozen=false;stable=0;cue=null;target=null;plan=null;drawing.visibility=View.VISIBLE;say(message)}
 inner class Guide:View(this){
  private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
  override fun onDraw(c:Canvas){
   paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;paint.color=Color.CYAN
   c.drawRect(table,paint)
   // Outside edges reduce interference with the next detection frame.
   balls.forEach{paint.color=if(it===cue)Color.WHITE else if(it===target)Color.YELLOW else Color.CYAN;c.drawCircle(it.x,it.y,it.r+5,paint)}
   plan?.let{p->paint.color=Color.YELLOW;c.drawLine(p.cue.x,p.cue.y,p.ghost.x,p.ghost.y,paint);c.drawCircle(p.ghost.x,p.ghost.y,p.cue.r,paint);paint.color=Color.GREEN;c.drawLine(p.target.x,p.target.y,p.pocket.x,p.pocket.y,paint)}
  }
  override fun onTouchEvent(e:MotionEvent):Boolean{
   if(e.action!=MotionEvent.ACTION_UP)return true
   val pt=PointF(e.rawX,e.rawY)
   when(mode){
    "table"->{if(first==null){first=pt;return true};val f=first!!;if(abs(pt.x-f.x)<screenW*.3 || abs(pt.y-f.y)<screenH*.3){say("Table selection too small; retry");first=null;return true};table=RectF(min(f.x,pt.x),min(f.y,pt.y),max(f.x,pt.x),max(f.y,pt.y));cue=null;target=null}
    "power"->{if(first==null){first=pt;return true};powerStart=first;powerEnd=pt}
    "cue","target"->{val selected=balls.minByOrNull{hypot(it.x-pt.x,it.y-pt.y)}?.takeIf{hypot(it.x-pt.x,it.y-pt.y)<it.r*3};if(selected==null){say("No detected ball here. Wait or recalibrate table.");return true};if(mode=="cue")cue=selected else target=selected;if(cue===target)target=null}
    "pocket"->pocket=pt
   }
   finishPick();return true
  }
 }
 override fun onDestroy(){
  cancelled=true;main.removeCallbacksAndMessages(null)
  display?.release();reader?.setOnImageAvailableListener(null,null);reader?.close();projection?.stop();projection=null
  worker.quitSafely()
  if(::drawing.isInitialized)runCatching{wm.removeView(drawing)}
  panel?.let{runCatching{wm.removeView(it)}}
  stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
 }
}
