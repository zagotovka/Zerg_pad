package com.example.zerg_pad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

public class ZergJoystickView extends View {
    // Direction constants
    public static final int CENTER = 0;
    public static final int RIGHT = 1;
    public static final int LEFT_FRONT = 2;
    public static final int FRONT = 3;
    public static final int FRONT_RIGHT = 4;
    public static final int LEFT = 5;
    public static final int RIGHT_BOTTOM = 6;
    public static final int BOTTOM = 7;
    public static final int BOTTOM_LEFT = 8;

    // Configuration constants
    private static final int DEFAULT_LOOP_INTERVAL = 50; // ms
    private static final int MIN_CHANGE_THRESHOLD = 5; // минимальное изменение для отправки
    private static final int DEFAULT_RAY_WIDTH = 10;
    private static final float BUTTON_SIZE_RATIO = 0.25f;
    private static final float JOYSTICK_SIZE_RATIO = 0.75f;
    private static final float INNER_CIRCLE_RATIO = 1.5f;
    private static final float ARROW_SIZE_RATIO = 0.8f;
    private static final float ARROW_POSITION_RATIO = 0.6f;

    // Drawing tools
    private final Paint mainCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint();
    private final Path rayPath = new Path();

    // Position tracking
    private int xPosition;
    private int yPosition;
    private double centerX;
    private double centerY;
    private int joystickRadius;
    private int buttonRadius;
    private float innerCircleRadius;

    // State management
    private OnJoystickMoveListener listener;
    private int lastAngle;
    private int lastPower;
    private long lastUpdateTime;
    private boolean isJoystickActive;
    private int lastSentAngle;
    private int lastSentPower;

    public ZergJoystickView(Context context) {
        super(context);
        init();
    }

    public ZergJoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ZergJoystickView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Main circle (gray background)
        mainCirclePaint.setColor(Color.parseColor("#7f7f7f"));
        mainCirclePaint.setStyle(Paint.Style.FILL_AND_STROKE);

        // Button (blue joystick)
        buttonPaint.setColor(Color.parseColor("#0066FF"));
        buttonPaint.setStyle(Paint.Style.FILL);

        // Arrows (white indicators)
        arrowPaint.setColor(Color.WHITE);
        arrowPaint.setStyle(Paint.Style.FILL);

        // Clear paint for transparent areas
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        clearPaint.setAntiAlias(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        centerX = w / 2.0;
        centerY = h / 2.0;
        xPosition = (int) centerX;
        yPosition = (int) centerY;

        int size = Math.min(w, h);
        buttonRadius = (int) (size / 2.0 * BUTTON_SIZE_RATIO);
        joystickRadius = (int) (size / 2.0 * JOYSTICK_SIZE_RATIO);
        innerCircleRadius = buttonRadius * INNER_CIRCLE_RATIO;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.min(measureDimension(widthMeasureSpec), measureDimension(heightMeasureSpec));
        setMeasuredDimension(size, size);
    }

    private int measureDimension(int measureSpec) {
        int mode = MeasureSpec.getMode(measureSpec);
        int size = MeasureSpec.getSize(measureSpec);
        return mode == MeasureSpec.UNSPECIFIED ? 200 : size;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawMainComponents(canvas);
        drawButton(canvas);
    }

    private void drawMainComponents(Canvas canvas) {
        // Draw main circle
        canvas.drawCircle((float) centerX, (float) centerY, joystickRadius, mainCirclePaint);

        // Draw transparent rays
        createRayPath();
        canvas.drawPath(rayPath, clearPaint);

        // Draw inner transparent circle
        canvas.drawCircle((float) centerX, (float) centerY, innerCircleRadius, clearPaint);

        // Draw direction arrows
        drawDirectionArrows(canvas);
    }

    private void createRayPath() {
        rayPath.reset();
        float halfWidth = DEFAULT_RAY_WIDTH / 2f;

        for (int angle = 45; angle < 360; angle += 90) {
            double radians = Math.toRadians(angle);
            float cos = (float) Math.cos(radians);
            float sin = (float) Math.sin(radians);

            float px = -sin * halfWidth;
            float py = cos * halfWidth;

            float cx = (float) centerX;
            float cy = (float) centerY;

            rayPath.moveTo(cx + px, cy + py);
            rayPath.lineTo(cx - px, cy - py);
            rayPath.lineTo(cx - px + cos * joystickRadius, cy - py + sin * joystickRadius);
            rayPath.lineTo(cx + px + cos * joystickRadius, cy + py + sin * joystickRadius);
            rayPath.close();
        }
    }

    private void drawDirectionArrows(Canvas canvas) {
        float arrowSize = buttonRadius * ARROW_SIZE_RATIO;
        float offset = joystickRadius * ARROW_POSITION_RATIO;

        // Top arrow
        drawArrow(canvas, (float) centerX, (float) centerY - offset, 0, arrowSize);
        // Right arrow
        drawArrow(canvas, (float) centerX + offset, (float) centerY, 90, arrowSize);
        // Bottom arrow
        drawArrow(canvas, (float) centerX, (float) centerY + offset, 180, arrowSize);
        // Left arrow
        drawArrow(canvas, (float) centerX - offset, (float) centerY, 270, arrowSize);
    }

    private void drawArrow(Canvas canvas, float x, float y, float rotation, float size) {
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(rotation);

        Path arrow = new Path();
        arrow.moveTo(0, -size);
        arrow.lineTo(size / 2, 0);
        arrow.lineTo(-size / 2, 0);
        arrow.close();

        canvas.drawPath(arrow, arrowPaint);
        canvas.restore();
    }

    private void drawButton(Canvas canvas) {
        canvas.drawCircle(xPosition, yPosition, buttonRadius, buttonPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                handleTouchDown(event);
                return true;

            case MotionEvent.ACTION_MOVE:
                handleTouchMove(event);
                return true;

            case MotionEvent.ACTION_UP:
                handleTouchUp();
                return performClick();
        }
        return super.onTouchEvent(event);
    }

    private void handleTouchDown(MotionEvent event) {
        isJoystickActive = true;
        updatePosition(event.getX(), event.getY());
        sendInitialPosition();
    }

    private void handleTouchMove(MotionEvent event) {
        updatePosition(event.getX(), event.getY());
        checkAndSendPositionUpdate();
    }

    private void handleTouchUp() {
        isJoystickActive = false;
        resetPosition();
        sendReleaseEvent();
    }

    private void updatePosition(float x, float y) {
        xPosition = (int) x;
        yPosition = (int) y;

        // Constrain to joystick circle
        double dx = xPosition - centerX;
        double dy = yPosition - centerY;
        double distance = Math.sqrt(dx * dx + dy * dy);

        if (distance > joystickRadius) {
            xPosition = (int) (centerX + dx * joystickRadius / distance);
            yPosition = (int) (centerY + dy * joystickRadius / distance);
        }

        invalidate();
    }

    private void sendInitialPosition() {
        int angle = calculateAngle();
        int power = calculatePower();

        sendPositionUpdate(angle, power);
        lastSentAngle = angle;
        lastSentPower = power;
        lastUpdateTime = System.currentTimeMillis();
    }

    private void checkAndSendPositionUpdate() {
        int newAngle = calculateAngle();
        int newPower = calculatePower();
        long currentTime = System.currentTimeMillis();

        boolean significantChange = Math.abs(newAngle - lastSentAngle) > MIN_CHANGE_THRESHOLD ||
                Math.abs(newPower - lastSentPower) > MIN_CHANGE_THRESHOLD;

        boolean timeElapsed = (currentTime - lastUpdateTime) >= DEFAULT_LOOP_INTERVAL;

        if (significantChange || timeElapsed) {
            sendPositionUpdate(newAngle, newPower);
            lastSentAngle = newAngle;
            lastSentPower = newPower;
            lastUpdateTime = currentTime;
        }
    }

    private void sendPositionUpdate(int angle, int power) {
        lastAngle = angle;
        lastPower = power;

        if (listener != null) {
            int direction = calculateDirection(angle, power);
            listener.onValueChanged(angle, power, direction);
            Log.d("Joystick", String.format("X: %+3d Y: %+3d Power: %3d",
                    getXValue(angle, power), getYValue(angle, power), power));
        }
    }

    private void sendReleaseEvent() {
        if (listener != null) {
            listener.onValueChanged(0, 0, CENTER);
            Log.d("Joystick", "Released: X: 0 Y: 0 Power: 0");
        }
        resetState();
    }

    private void resetPosition() {
        xPosition = (int) centerX;
        yPosition = (int) centerY;
        invalidate();
    }

    private void resetState() {
        lastAngle = 0;
        lastPower = 0;
        lastSentAngle = 0;
        lastSentPower = 0;
        lastUpdateTime = 0;
    }

    private int calculateAngle() {
        double dx = xPosition - centerX;
        double dy = yPosition - centerY;

        if (dx == 0 && dy == 0) return 0;

        double angle = Math.toDegrees(Math.atan2(-dy, dx));
        return (int) ((angle + 450) % 360); // Normalize to 0-360
    }

    private int calculatePower() {
        double dx = xPosition - centerX;
        double dy = yPosition - centerY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        return (int) Math.min(100, (distance / joystickRadius) * 100);
    }

    private int calculateDirection(int angle, int power) {
        if (power == 0) return CENTER;

        int sector = ((angle + 22) / 45) % 8;
        switch (sector) {
            case 0: return FRONT;
            case 1: return FRONT_RIGHT;
            case 2: return RIGHT;
            case 3: return RIGHT_BOTTOM;
            case 4: return BOTTOM;
            case 5: return BOTTOM_LEFT;
            case 6: return LEFT;
            case 7: return LEFT_FRONT;
            default: return CENTER;
        }
    }

    private int getXValue(int angle, int power) {
        return (int) (Math.cos(Math.toRadians(angle)) * power);
    }

    private int getYValue(int angle, int power) {
        return (int) (-Math.sin(Math.toRadians(angle)) * power);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    public void setOnJoystickMoveListener(OnJoystickMoveListener listener) {
        this.listener = listener;
    }

    public interface OnJoystickMoveListener {
        void onValueChanged(int angle, int power, int direction);
    }
}