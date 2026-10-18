package app.anibrave;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small consistent stroke icons; independent of system font glyphs. */
final class TabIcon extends Drawable {
    static final int COLLAPSE=0, EXPAND=1, PLUS=2, MORE=3, TABS=4, PRIVATE=5, CLOSE=6, SETTINGS=7, GLOBE=8, OVERVIEW=9, REGULAR=10;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int kind;
    private final int size;
    TabIcon(int kind,int color,int size){this.kind=kind;this.size=size;paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
    @Override public void draw(Canvas canvas){
        canvas.save();Rect b=getBounds();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);
        switch(kind){
            case COLLAPSE:case EXPAND:
                canvas.drawRoundRect(3,4,21,20,3,3,paint);canvas.drawLine(9,4,9,20,paint);
                Path arrow=new Path();float x=kind==COLLAPSE?15:14,dx=kind==COLLAPSE?-2:2;
                arrow.moveTo(x,9);arrow.lineTo(x+dx,12);arrow.lineTo(x,15);canvas.drawPath(arrow,paint);break;
            case PLUS:canvas.drawLine(12,5,12,19,paint);canvas.drawLine(5,12,19,12,paint);break;
            case MORE:
                paint.setStyle(Paint.Style.FILL);for(int dot=5;dot<=19;dot+=7)canvas.drawCircle(dot,12,1.5f,paint);paint.setStyle(Paint.Style.STROKE);break;
            case TABS:canvas.drawRoundRect(5,6,21,21,3,3,paint);Path back=new Path();back.moveTo(3,17);back.lineTo(3,6);back.quadTo(3,3,6,3);back.lineTo(17,3);canvas.drawPath(back,paint);break;
            case PRIVATE:
                Path mask=new Path();mask.moveTo(3,9);mask.quadTo(7,7,12,10);mask.quadTo(17,7,21,9);mask.lineTo(20,15);mask.quadTo(17,20,12,15);mask.quadTo(7,20,4,15);mask.close();canvas.drawPath(mask,paint);
                canvas.drawLine(6,12,9,13,paint);canvas.drawLine(15,13,18,12,paint);break;
            case OVERVIEW:
                for(int col=4;col<=14;col+=10)for(int row=4;row<=14;row+=10)canvas.drawRoundRect(col,row,col+6,row+6,1.3f,1.3f,paint);break;
            case REGULAR:
                canvas.drawCircle(12,8,3,paint);Path person=new Path();person.moveTo(5,21);person.lineTo(5,19);person.cubicTo(5,12,19,12,19,19);person.lineTo(19,21);canvas.drawPath(person,paint);break;
            case SETTINGS:
                canvas.drawLine(4,6,20,6,paint);canvas.drawLine(4,12,20,12,paint);canvas.drawLine(4,18,20,18,paint);
                canvas.drawLine(9,3,9,9,paint);canvas.drawLine(15,9,15,15,paint);canvas.drawLine(9,15,9,21,paint);break;
            case GLOBE:
                canvas.drawCircle(12,12,9,paint);canvas.drawOval(8,3,16,21,paint);canvas.drawLine(3,12,21,12,paint);break;
            case CLOSE:canvas.drawLine(7,7,17,17,paint);canvas.drawLine(17,7,7,17,paint);break;
        }
        canvas.restore();
    }
    @Override public void setAlpha(int value){paint.setAlpha(value);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter value){paint.setColorFilter(value);invalidateSelf();}
    @Override public int getIntrinsicWidth(){return size;}
    @Override public int getIntrinsicHeight(){return size;}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
