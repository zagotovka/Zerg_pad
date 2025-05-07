package com.example.zerg_pad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class ZergJoystickView extends View implements Runnable {

    public static final long DEFAULT_LOOP_INTERVAL = 100;
    public static final int FRONT = 3;
    public static final int FRONT_RIGHT = 4;
    public static final int RIGHT = 5;
    public static final int RIGHT_BOTTOM = 6;
    public static final int BOTTOM = 7;
    public static final int BOTTOM_LEFT = 8;
    public static final int LEFT = 1;
    public static final int LEFT_FRONT = 2;
    private static final int DEFAULT_RAY_WIDTH = 10; // Ширина лучей.

    private int xPosition = 0;
    private int yPosition = 0;
    private double centerX = 0;
    private double centerY = 0;
    private int joystickRadius;
    private int buttonRadius;
    private float baseRadius;
    private float innerCircleRadius;
    private Path rayPath;
    private Paint mainCircle;
    private Paint button;
    private Paint arrowPaint;
    private Paint clearPaint;
    private OnJoystickMoveListener onJoystickMoveListener;
    private Thread thread;
    private long loopInterval = DEFAULT_LOOP_INTERVAL;
    private int lastAngle = 0;
    private int lastPower = 0;

    public ZergJoystickView(Context context) {
        super(context);
        initJoystickView();
    }

    public ZergJoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initJoystickView();
    }

    public ZergJoystickView(Context context, AttributeSet attrs, int defaultStyle) {
        super(context, attrs, defaultStyle);
        initJoystickView();
    }

    protected void initJoystickView() {
        mainCircle = new Paint(Paint.ANTI_ALIAS_FLAG);
        mainCircle.setColor(Color.parseColor("#7f7f7f"));
        mainCircle.setStyle(Paint.Style.FILL_AND_STROKE);

        button = new Paint(Paint.ANTI_ALIAS_FLAG);
        button.setColor(Color.parseColor("#0066FF"));
        button.setStyle(Paint.Style.FILL);

        arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(Color.WHITE);
        arrowPaint.setStyle(Paint.Style.FILL);

        clearPaint = new Paint();
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        clearPaint.setAntiAlias(true);

        rayPath = new Path();
    }

    @Override
    protected void onSizeChanged(int xNew, int yNew, int xOld, int yOld) {
        super.onSizeChanged(xNew, yNew, xOld, yOld);

        xPosition = getWidth() / 2;
        yPosition = getHeight() / 2;

        int d = Math.min(xNew, yNew);
        buttonRadius = (int) (d / 2.0 * 0.25);       // ← размер синей кнопки (оставь как есть или меняй отдельно)
        joystickRadius = (int) (d / 2.0 * 0.75);     // ← ВНЕШНИЙ радиус серого круга (mainCircle)

        baseRadius = joystickRadius;
        innerCircleRadius = buttonRadius * 1.5f;   // ← ВНУТРЕННИЙ радиус (прозрачный круг внутри)
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int d = Math.min(measure(widthMeasureSpec), measure(heightMeasureSpec));
        setMeasuredDimension(d, d);
    }

    private int measure(int measureSpec) {
        int specMode = MeasureSpec.getMode(measureSpec);
        int specSize = MeasureSpec.getSize(measureSpec);
        return (specMode == MeasureSpec.UNSPECIFIED) ? 200 : specSize;
    }

    private void createRayPath() {
        rayPath.reset();
        float rayWidth = DEFAULT_RAY_WIDTH;

        // Углы: 45°, 135°, 225°, 315°
        for (int angle = 45; angle < 360; angle += 90) {
            double rad = Math.toRadians(angle);

            // Вектор направления луча
            float dx = (float) Math.cos(rad);
            float dy = (float) Math.sin(rad);

            // Перпендикулярный вектор (для ширины луча)
            float px = -dy;
            float py = dx;

            // Длина луча = радиус круга
            float r = baseRadius;

            // Центр
            float cx = (float) centerX;
            float cy = (float) centerY;

            // 4 точки прямоугольного луча
            float x1 = cx + px * (rayWidth / 2);
            float y1 = cy + py * (rayWidth / 2);
            float x2 = cx - px * (rayWidth / 2);
            float y2 = cy - py * (rayWidth / 2);
            float x3 = x2 + dx * r;
            float y3 = y2 + dy * r;
            float x4 = x1 + dx * r;
            float y4 = y1 + dy * r;

            // Добавляем прямоугольник в path
            rayPath.moveTo(x1, y1);
            rayPath.lineTo(x2, y2);
            rayPath.lineTo(x3, y3);
            rayPath.lineTo(x4, y4);
            rayPath.close();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        centerX = getWidth() / 2.0;
        centerY = getHeight() / 2.0;

        canvas.drawCircle((float) centerX, (float) centerY, joystickRadius, mainCircle);

        createRayPath();
        canvas.drawPath(rayPath, clearPaint);
        canvas.drawCircle((float) centerX, (float) centerY, innerCircleRadius, clearPaint);

        drawArrows(canvas);
        canvas.drawCircle((float) xPosition, (float) yPosition, buttonRadius, button);
    }


    private void drawArrows(Canvas canvas) {
        float arrowSize = buttonRadius * 0.8f;

        drawArrow(canvas, (float) centerX, (float) (centerY - joystickRadius * 0.6), 0, arrowSize);
        drawArrow(canvas, (float) (centerX + joystickRadius * 0.6), (float) centerY, 90, arrowSize);
        drawArrow(canvas, (float) centerX, (float) (centerY + joystickRadius * 0.6), 180, arrowSize);
        drawArrow(canvas, (float) (centerX - joystickRadius * 0.6), (float) centerY, 270, arrowSize);
    }

    private void drawArrow(Canvas canvas, float x, float y, int rotation, float size) {
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(rotation);

        Path arrow = new Path();
        arrow.moveTo(0, -size);
        arrow.lineTo(size / 2.0f, 0);
        arrow.lineTo(-size / 2.0f, 0);
        arrow.close();

        canvas.drawPath(arrow, arrowPaint);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        xPosition = (int) event.getX();
        yPosition = (int) event.getY();

        double abs = Math.sqrt(Math.pow(xPosition - centerX, 2) + Math.pow(yPosition - centerY, 2));
        if (abs > joystickRadius) {
            xPosition = (int) ((xPosition - centerX) * joystickRadius / abs + centerX);
            yPosition = (int) ((yPosition - centerY) * joystickRadius / abs + centerY);
        }

        invalidate();

        if (event.getAction() == MotionEvent.ACTION_UP) {
            xPosition = (int) centerX;
            yPosition = (int) centerY;
            lastAngle = 0;
            lastPower = 0;

            if (thread != null) thread.interrupt();

            if (onJoystickMoveListener != null) {
                onJoystickMoveListener.onValueChanged(0, 0, 0);
            }

            performClick();
            invalidate();
        } else if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (thread != null && thread.isAlive()) {
                thread.interrupt();
            }

            thread = new Thread(this);
            thread.start();

            if (onJoystickMoveListener != null) {
                onJoystickMoveListener.onValueChanged(getAngle(), getPower(), getDirection());
            }
        }

        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private int getAngle() {
        final double RAD = 57.2957795;

        if (xPosition > centerX) {
            if (yPosition < centerY) {
                return lastAngle = (int) (Math.atan((yPosition - centerY) / (xPosition - centerX)) * RAD + 90);
            } else if (yPosition > centerY) {
                return lastAngle = (int) (Math.atan((yPosition - centerY) / (xPosition - centerX)) * RAD) + 90;
            } else {
                return lastAngle = 90;
            }
        } else if (xPosition < centerX) {
            if (yPosition < centerY) {
                return lastAngle = (int) (Math.atan((yPosition - centerY) / (xPosition - centerX)) * RAD - 90);
            } else if (yPosition > centerY) {
                return lastAngle = (int) (Math.atan((yPosition - centerY) / (xPosition - centerX)) * RAD) - 90;
            } else {
                return lastAngle = -90;
            }
        } else {
            if (yPosition <= centerY) {
                return lastAngle = 0;
            } else {
                return lastAngle = (lastAngle < 0) ? -180 : 180;
            }
        }
    }

    private int getPower() {
        lastPower = (int) (100 * Math.sqrt(Math.pow(xPosition - centerX, 2) + Math.pow(yPosition - centerY, 2)) / joystickRadius);
        return lastPower;
    }

    private int getDirection() {
        if (lastPower == 0 && lastAngle == 0) {
            return 0;
        }

        int a;
        if (lastAngle <= 0) {
            a = -lastAngle + 90;
        } else {
            a = (lastAngle <= 90) ? 90 - lastAngle : 360 - (lastAngle - 90);
        }

        int direction = ((a + 22) / 45) + 1;
        if (direction > 8) {
            direction = 1;
        }

        return direction;
    }

    public void setOnJoystickMoveListener(OnJoystickMoveListener listener, long repeatInterval) {
        this.onJoystickMoveListener = listener;
        this.loopInterval = repeatInterval;
    }

    public interface OnJoystickMoveListener {
        void onValueChanged(int angle, int power, int direction);
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            post(() -> {
                if (onJoystickMoveListener != null) {
                    onJoystickMoveListener.onValueChanged(getAngle(), getPower(), getDirection());
                }
            });

            try {
                Thread.sleep(loopInterval);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
    }
}