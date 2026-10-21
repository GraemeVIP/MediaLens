package io.github.graemevip.medialens;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.content.res.ColorStateList;
import android.view.*;
import android.widget.*;

/** Small native components shared by the download and library screens. */
final class Ui {
    static final int BG=Color.rgb(11,17,23), SURFACE=Color.rgb(21,30,39), BORDER=Color.rgb(38,51,63);
    static final int TEXT=Color.rgb(242,247,250), MUTED=Color.rgb(164,181,195), ACCENT=Color.rgb(166,243,201);
    static int dp(Context c,int n){return Math.round(c.getResources().getDisplayMetrics().density*n);}
    static GradientDrawable shape(Context c,int color,int radius,boolean border){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));if(border)d.setStroke(dp(c,1),BORDER);return d;
    }
    static TextView text(Context c,String value,int size,boolean bold){
        TextView t=new TextView(c);t.setText(value);t.setTextColor(TEXT);t.setTextSize(size);
        t.setFontFeatureSettings("kern");t.setLineSpacing(dp(c,3),1f);t.setIncludeFontPadding(false);
        t.setTypeface(android.graphics.Typeface.create(bold?"sans-serif-medium":"sans-serif",bold?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL));return t;
    }
    static void gap(LinearLayout l,int n){Space s=new Space(l.getContext());l.addView(s,new LinearLayout.LayoutParams(1,dp(l.getContext(),n)));}
    static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout row(Context c){LinearLayout l=new LinearLayout(c);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    static void ripple(View v,int color,int radius){v.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(40,180,230,210)),shape(v.getContext(),color,radius,false),null));v.setClickable(true);v.setFocusable(true);}
    static TextView button(Context c,String value,boolean primary){
        TextView b=text(c,value,17,true);b.setGravity(Gravity.CENTER);b.setPadding(dp(c,16),dp(c,14),dp(c,16),dp(c,14));
        b.setTextColor(primary?BG:TEXT);ripple(b,primary?ACCENT:SURFACE,18);b.setMinHeight(dp(c,56));return b;
    }
    static FrameLayout iconButton(Context c,String icon,String description,Runnable click){
        FrameLayout f=new FrameLayout(c);f.setContentDescription(description);ripple(f,Color.TRANSPARENT,16);
        Icon i=new Icon(c,icon,MUTED);FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(c,23),dp(c,23),Gravity.CENTER);f.addView(i,ip);f.setOnClickListener(v->click.run());f.setLayoutParams(new LinearLayout.LayoutParams(dp(c,48),dp(c,48)));return f;
    }
    static final class Icon extends View {
        final String type;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final int color;
        Icon(Context c,String type,int color){super(c);this.type=type;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);c.save();c.scale(getWidth()/24f,getHeight()/24f);p.setColor(color);p.setStrokeWidth(1.7f);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            switch(type){
                case "download":line(c,12,3,12,15);path(c,7,10,12,15,17,10);path(c,4,15,4,20,20,20,20,15);break;
                case "home":path(c,3,10,12,3,21,10);path(c,5,9,5,21,10,21,10,14,14,14,14,21,19,21,19,9);break;
                case "link":c.save();c.rotate(-40,12,12);c.drawRoundRect(5,3,19,11,4,4,p);c.drawRoundRect(5,13,19,21,4,4,p);line(c,12,8,12,16);c.restore();break;
                case "paste":c.drawRoundRect(5,5,19,22,2,2,p);c.drawRoundRect(8,2,16,7,1,1,p);line(c,9,12,15,12);line(c,9,16,14,16);break;
                case "arrow":line(c,4,12,20,12);path(c,14,6,20,12,14,18);break;
                case "chevron":path(c,6,9,12,15,18,9);break;
                case "close":line(c,6,6,18,18);line(c,18,6,6,18);break;
                case "check":path(c,5,12,10,17,20,6);break;
                case "video":c.drawRoundRect(3,4,21,20,4,4,p);path(c,10,8,16,12,10,16,10,8);break;
                case "image":c.drawRoundRect(3,3,21,21,4,4,p);c.drawCircle(8,8,1.5f,p);path(c,4,18,10,12,14,16,17,12,21,16);break;
                case "audio":path(c,9,17,9,5,20,3,20,15);c.drawOval(3,16,9,21,p);c.drawOval(14,14,20,19,p);line(c,9,9,20,7);break;
                case "folder":path(c,3,20,3,5,10,5,12,8,21,8,21,20,3,20);break;
                case "external":path(c,13,3,21,3,21,11);line(c,21,3,10,14);path(c,9,5,4,5,4,21,20,21,20,16);break;
                case "retry":c.drawArc(4,4,20,20,40,290,false,p);path(c,4,3,4,9,10,9);break;
                case "more":p.setStyle(Paint.Style.FILL);c.drawCircle(5,12,1.8f,p);c.drawCircle(12,12,1.8f,p);c.drawCircle(19,12,1.8f,p);break;
                case "settings":c.drawCircle(12,12,4,p);for(int a=0;a<8;a++){c.save();c.rotate(a*45,12,12);line(c,12,2,12,5);c.restore();}break;
                case "globe":c.drawCircle(12,12,9,p);c.drawOval(8,3,16,21,p);line(c,3,12,21,12);break;
                case "trash":path(c,6,7,7,21,17,21,18,7);line(c,4,6,20,6);path(c,9,6,9,3,15,3,15,6);line(c,10,10,10,17);line(c,14,10,14,17);break;
            }c.restore();
        }
        private void line(Canvas c,float x,float y,float xx,float yy){c.drawLine(x,y,xx,yy,p);}
        private void path(Canvas c,float... xy){Path path=new Path();path.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)path.lineTo(xy[i],xy[i+1]);c.drawPath(path,p);}
    }
}
