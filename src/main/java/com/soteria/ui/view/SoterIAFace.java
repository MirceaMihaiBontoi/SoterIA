package com.soteria.ui.view;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;

/**
 * Minimalist animated face for the voice-first shell.
 *
 * The face itself is intentionally tiny (disc + two dot eyes + arc mouth); the
 * "neon" feel is carried by a soft outer glow and a ring that pulses outward
 * when SoterIA is listening. Five states cover the full voice loop: idle,
 * listening, thinking, speaking, alert. Each state owns a single Timeline;
 * switching states stops the current one and resets transient properties
 * before the next one runs, so transitions are always clean.
 */
public final class SoterIAFace extends StackPane {

    public enum State { IDLE, LISTENING, THINKING, SPEAKING, ALERT }

    private static final Color ACCENT = Color.web("#06b6d4");
    private static final Color DANGER = Color.web("#ef4444");
    private static final Color WARNING = Color.web("#f59e0b");
    private static final Color DARK = Color.web("#0a1214");

    private final Circle outerGlow = new Circle();
    private final Circle ring = new Circle();
    private final Circle face = new Circle();
    private final Circle leftEye = new Circle();
    private final Circle rightEye = new Circle();
    private final Arc mouth = new Arc();

    private final double baseRadius;
    private final double eyeOffsetX;
    private final double eyeOffsetY;

    private Timeline activeAnimation;
    private final ObjectProperty<State> state = new SimpleObjectProperty<>(State.IDLE);

    public SoterIAFace() {
        this(85);
    }

    public SoterIAFace(double faceRadius) {
        this.baseRadius = faceRadius;
        this.eyeOffsetX = faceRadius * 0.32;
        this.eyeOffsetY = faceRadius * 0.18;

        setAlignment(Pos.CENTER);
        setPickOnBounds(false);

        outerGlow.setRadius(faceRadius * 1.9);
        outerGlow.setFill(Color.TRANSPARENT);
        outerGlow.setStroke(ACCENT);
        outerGlow.setStrokeWidth(2);
        outerGlow.setOpacity(0.2);

        ring.setRadius(faceRadius * 1.3);
        ring.setFill(Color.TRANSPARENT);
        ring.setStroke(ACCENT);
        ring.setStrokeWidth(1.5);
        ring.setOpacity(0.5);

        face.setRadius(faceRadius);
        face.setFill(ACCENT);
        applyShadow(face, ACCENT);

        double eyeR = faceRadius * 0.11;
        leftEye.setRadius(eyeR);
        leftEye.setFill(DARK);
        leftEye.setTranslateX(-eyeOffsetX);
        leftEye.setTranslateY(-eyeOffsetY);

        rightEye.setRadius(eyeR);
        rightEye.setFill(DARK);
        rightEye.setTranslateX(eyeOffsetX);
        rightEye.setTranslateY(-eyeOffsetY);

        mouth.setRadiusX(faceRadius * 0.36);
        mouth.setRadiusY(faceRadius * 0.22);
        mouth.setStartAngle(200);
        mouth.setLength(140);
        mouth.setType(ArcType.OPEN);
        mouth.setFill(Color.TRANSPARENT);
        mouth.setStroke(DARK);
        mouth.setStrokeWidth(3);
        mouth.setStrokeLineCap(StrokeLineCap.ROUND);
        mouth.setTranslateY(faceRadius * 0.32);

        getChildren().addAll(outerGlow, ring, face, leftEye, rightEye, mouth);

        state.addListener((obs, oldState, newState) -> applyState(newState));
        applyState(State.IDLE);
    }

    public void setState(State newState) {
        state.set(newState);
    }

    public State getState() {
        return state.get();
    }

    public ObjectProperty<State> stateProperty() {
        return state;
    }

    private void applyShadow(Circle target, Color c) {
        DropShadow glow = new DropShadow(40, c.deriveColor(0, 1, 1, 0.55));
        glow.setSpread(0.15);
        target.setEffect(glow);
    }

    private void tint(Color c) {
        face.setFill(c);
        applyShadow(face, c);
        outerGlow.setStroke(c);
        ring.setStroke(c);
    }

    private void resetTransient() {
        if (activeAnimation != null) {
            activeAnimation.stop();
            activeAnimation = null;
        }
        face.setScaleX(1);
        face.setScaleY(1);
        outerGlow.setOpacity(0.2);
        outerGlow.setScaleX(1);
        outerGlow.setScaleY(1);
        ring.setOpacity(0.5);
        ring.setScaleX(1);
        ring.setScaleY(1);
        leftEye.setTranslateX(-eyeOffsetX);
        leftEye.setTranslateY(-eyeOffsetY);
        rightEye.setTranslateX(eyeOffsetX);
        rightEye.setTranslateY(-eyeOffsetY);
        mouth.setStartAngle(200);
        mouth.setLength(140);
    }

    private void applyState(State s) {
        resetTransient();
        switch (s) {
            case IDLE -> startIdle();
            case LISTENING -> startListening();
            case THINKING -> startThinking();
            case SPEAKING -> startSpeaking();
            case ALERT -> startAlert();
        }
    }

    private void startIdle() {
        tint(ACCENT);
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(face.scaleXProperty(), 1.0),
                        new KeyValue(face.scaleYProperty(), 1.0),
                        new KeyValue(outerGlow.opacityProperty(), 0.15)),
                new KeyFrame(Duration.seconds(1.6),
                        new KeyValue(face.scaleXProperty(), 1.035, Interpolator.EASE_BOTH),
                        new KeyValue(face.scaleYProperty(), 1.035, Interpolator.EASE_BOTH),
                        new KeyValue(outerGlow.opacityProperty(), 0.35, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.seconds(3.2),
                        new KeyValue(face.scaleXProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(face.scaleYProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(outerGlow.opacityProperty(), 0.15, Interpolator.EASE_BOTH))
        );
        tl.setCycleCount(Animation.INDEFINITE);
        tl.play();
        activeAnimation = tl;
    }

    private void startListening() {
        tint(ACCENT);
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(ring.scaleXProperty(), 1.0),
                        new KeyValue(ring.scaleYProperty(), 1.0),
                        new KeyValue(ring.opacityProperty(), 0.75),
                        new KeyValue(outerGlow.opacityProperty(), 0.25),
                        new KeyValue(outerGlow.scaleXProperty(), 1.0),
                        new KeyValue(outerGlow.scaleYProperty(), 1.0)),
                new KeyFrame(Duration.millis(1100),
                        new KeyValue(ring.scaleXProperty(), 1.45, Interpolator.EASE_OUT),
                        new KeyValue(ring.scaleYProperty(), 1.45, Interpolator.EASE_OUT),
                        new KeyValue(ring.opacityProperty(), 0.0, Interpolator.EASE_OUT),
                        new KeyValue(outerGlow.opacityProperty(), 0.55, Interpolator.EASE_OUT),
                        new KeyValue(outerGlow.scaleXProperty(), 1.15, Interpolator.EASE_OUT),
                        new KeyValue(outerGlow.scaleYProperty(), 1.15, Interpolator.EASE_OUT))
        );
        tl.setCycleCount(Animation.INDEFINITE);
        tl.play();
        activeAnimation = tl;
    }

    private void startThinking() {
        tint(WARNING);
        leftEye.setTranslateX(-eyeOffsetX * 0.6);
        leftEye.setTranslateY(-eyeOffsetY * 1.6);
        rightEye.setTranslateX(eyeOffsetX * 1.3);
        rightEye.setTranslateY(-eyeOffsetY * 1.6);
        mouth.setStartAngle(170);
        mouth.setLength(40);
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(outerGlow.opacityProperty(), 0.2),
                        new KeyValue(ring.opacityProperty(), 0.4)),
                new KeyFrame(Duration.millis(700),
                        new KeyValue(outerGlow.opacityProperty(), 0.55, Interpolator.EASE_BOTH),
                        new KeyValue(ring.opacityProperty(), 0.15, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(1400),
                        new KeyValue(outerGlow.opacityProperty(), 0.2, Interpolator.EASE_BOTH),
                        new KeyValue(ring.opacityProperty(), 0.4, Interpolator.EASE_BOTH))
        );
        tl.setCycleCount(Animation.INDEFINITE);
        tl.play();
        activeAnimation = tl;
    }

    private void startSpeaking() {
        tint(ACCENT);
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(mouth.lengthProperty(), 60, Interpolator.EASE_BOTH),
                        new KeyValue(mouth.startAngleProperty(), 240, Interpolator.EASE_BOTH),
                        new KeyValue(outerGlow.opacityProperty(), 0.25)),
                new KeyFrame(Duration.millis(200),
                        new KeyValue(mouth.lengthProperty(), 170, Interpolator.EASE_BOTH),
                        new KeyValue(mouth.startAngleProperty(), 185, Interpolator.EASE_BOTH),
                        new KeyValue(outerGlow.opacityProperty(), 0.5, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(400),
                        new KeyValue(mouth.lengthProperty(), 60, Interpolator.EASE_BOTH),
                        new KeyValue(mouth.startAngleProperty(), 240, Interpolator.EASE_BOTH),
                        new KeyValue(outerGlow.opacityProperty(), 0.25, Interpolator.EASE_BOTH))
        );
        tl.setCycleCount(Animation.INDEFINITE);
        tl.play();
        activeAnimation = tl;
    }

    private void startAlert() {
        tint(DANGER);
        mouth.setStartAngle(160);
        mouth.setLength(40);
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(outerGlow.opacityProperty(), 0.35),
                        new KeyValue(outerGlow.scaleXProperty(), 1.0),
                        new KeyValue(outerGlow.scaleYProperty(), 1.0),
                        new KeyValue(face.scaleXProperty(), 1.0),
                        new KeyValue(face.scaleYProperty(), 1.0)),
                new KeyFrame(Duration.millis(380),
                        new KeyValue(outerGlow.opacityProperty(), 0.95, Interpolator.EASE_BOTH),
                        new KeyValue(outerGlow.scaleXProperty(), 1.25, Interpolator.EASE_BOTH),
                        new KeyValue(outerGlow.scaleYProperty(), 1.25, Interpolator.EASE_BOTH),
                        new KeyValue(face.scaleXProperty(), 1.06, Interpolator.EASE_BOTH),
                        new KeyValue(face.scaleYProperty(), 1.06, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(760),
                        new KeyValue(outerGlow.opacityProperty(), 0.35, Interpolator.EASE_BOTH),
                        new KeyValue(outerGlow.scaleXProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(outerGlow.scaleYProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(face.scaleXProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(face.scaleYProperty(), 1.0, Interpolator.EASE_BOTH))
        );
        tl.setCycleCount(Animation.INDEFINITE);
        tl.play();
        activeAnimation = tl;
    }

    public double preferredDiameter() {
        return baseRadius * 4;
    }
}
