package com.stereopairfinder;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import kotlin.Unit;
import kotlin.jvm.functions.Function2;

public final class SwapActivity extends ComponentActivity {
    private Object vm;
    private Button swapButton;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        ComposeView compose = new ComposeView(this);
        root.addView(compose, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        try {
            @SuppressWarnings("unchecked")
            Function2<?, ?, Unit> content = (Function2<?, ?, Unit>) findOriginalContent();
            compose.setContent(content);
        } catch (Throwable t) {
            throw new RuntimeException("Stereo Pair Finder arayüzü başlatılamadı", t);
        }

        swapButton = new Button(this);
        swapButton.setText("⇄");
        swapButton.setTextSize(25f);
        swapButton.setAllCaps(false);
        swapButton.setContentDescription("Sol ve sağ fotoğrafın yerini değiştir");
        swapButton.setVisibility(View.GONE);
        swapButton.setElevation(dp(8));
        swapButton.setOnClickListener(v -> swapCurrentResult());

        FrameLayout.LayoutParams buttonLp = new FrameLayout.LayoutParams(dp(64), dp(54));
        buttonLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        buttonLp.setMargins(0, 0, dp(14), 0);
        root.addView(swapButton, buttonLp);
        setContentView(root);

        try {
            Class<?> vmClass = Class.forName("com.stereopairfinder.MainViewModel");
            @SuppressWarnings({"rawtypes", "unchecked"})
            ViewModel model = new ViewModelProvider(this).get((Class) vmClass);
            vm = model;
        } catch (Throwable t) {
            Toast.makeText(this, "Değiştir düğmesi hazırlanamadı", Toast.LENGTH_LONG).show();
        }

        handler.post(pollVisibility);
    }

    private Object findOriginalContent() throws Exception {
        Class<?> singletonClass = Class.forName("com.stereopairfinder.ComposableSingletons$MainActivityKt");
        Object singleton = null;
        for (Field f : singletonClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == singletonClass) {
                f.setAccessible(true);
                singleton = f.get(null);
                if (singleton != null) break;
            }
        }
        if (singleton == null) {
            try {
                Field instance = singletonClass.getDeclaredField("INSTANCE");
                instance.setAccessible(true);
                singleton = instance.get(null);
            } catch (Throwable ignored) { }
        }

        List<Object> candidates = new ArrayList<>();
        for (Field f : singletonClass.getDeclaredFields()) {
            if (Function2.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Object target = Modifier.isStatic(f.getModifiers()) ? null : singleton;
                Object value = f.get(target);
                if (value != null) {
                    String n = f.getName();
                    if (n.contains("lambda-2") || n.contains("lambda2") || n.equals("f92lambda2")) {
                        return value;
                    }
                    candidates.add(value);
                }
            }
        }
        if (candidates.size() >= 2) return candidates.get(1);
        if (candidates.size() == 1) return candidates.get(0);

        for (Method m : singletonClass.getDeclaredMethods()) {
            if (Function2.class.isAssignableFrom(m.getReturnType()) && m.getParameterCount() == 0) {
                m.setAccessible(true);
                Object target = Modifier.isStatic(m.getModifiers()) ? null : singleton;
                Object value = m.invoke(target);
                if (value != null) {
                    String n = m.getName();
                    if (n.contains("lambda-2") || n.contains("lambda2")) return value;
                    candidates.add(value);
                }
            }
        }
        if (candidates.size() >= 2) return candidates.get(1);
        if (!candidates.isEmpty()) return candidates.get(0);
        throw new IllegalStateException("Original Compose içeriği bulunamadı");
    }

    private final Runnable pollVisibility = new Runnable() {
        @Override public void run() {
            boolean visible = false;
            try {
                Object state = currentState();
                if (state != null) {
                    List<?> results = (List<?>) invoke0(state, "getResults");
                    visible = results != null && !results.isEmpty();
                }
            } catch (Throwable ignored) { }
            if (swapButton != null) swapButton.setVisibility(visible ? View.VISIBLE : View.GONE);
            handler.postDelayed(this, 350L);
        }
    };

    private Object currentState() throws Exception {
        if (vm == null) return null;
        Object stateFlow = invoke0(vm, "getState");
        if (stateFlow == null) return null;
        return invoke0(stateFlow, "getValue");
    }

    private void swapCurrentResult() {
        try {
            Object state = currentState();
            if (state == null) return;
            List<?> results = (List<?>) invoke0(state, "getResults");
            if (results == null || results.isEmpty()) return;

            Object result = results.get(0);
            Object pair = invoke0(result, "getPair");
            Object leftPhoto = invoke0(pair, "getLeft");
            Object rightPhoto = invoke0(pair, "getRight");
            int index = ((Number) invoke0(pair, "getIndex")).intValue();
            Object swappedPair = constructByArity(pair.getClass(), 3,
                    new Object[]{index, rightPhoto, leftPhoto});

            Bitmap leftPreview = (Bitmap) invoke0(result, "getLeftPreview");
            Bitmap rightPreview = (Bitmap) invoke0(result, "getRightPreview");
            Object swappedResult = constructByArity(result.getClass(), 11, new Object[]{
                    swappedPair,
                    invoke0(result, "getSimilarity"),
                    invoke0(result, "getReliableMatches"),
                    invoke0(result, "getAlignmentConfidence"),
                    invoke0(result, "getMedianVerticalError"),
                    invoke0(result, "getCommonAreaRatio"),
                    invoke0(result, "getStatus"),
                    rightPreview,
                    leftPreview,
                    invoke0(result, "getSbsPreview"),
                    invoke0(result, "getSbsJpeg")
            });

            ArrayList<Object> newResults = new ArrayList<>(results.size());
            newResults.addAll((List<?>) results);
            newResults.set(0, swappedResult);

            Object newState = constructByArity(state.getClass(), 14, new Object[]{
                    invoke0(state, "getSelected"),
                    newResults,
                    invoke0(state, "getMaxSeconds"),
                    invoke0(state, "getSimilarity"),
                    invoke0(state, "getBusy"),
                    invoke0(state, "getStage"),
                    invoke0(state, "getMessage"),
                    invoke0(state, "getProgress"),
                    invoke0(state, "getPhotoCount"),
                    invoke0(state, "getCandidateCount"),
                    invoke0(state, "getMatchedCount"),
                    invoke0(state, "getSavedCount"),
                    invoke0(state, "getFailedCount"),
                    invoke0(state, "getResumeAvailable")
            });

            Field stateField = null;
            for (Field f : vm.getClass().getDeclaredFields()) {
                String name = f.getName();
                if (name.equals("_state") || name.contains("state")) {
                    f.setAccessible(true);
                    Object candidate = f.get(vm);
                    if (candidate != null && hasOneArgMethod(candidate.getClass(), "setValue")) {
                        stateField = f;
                        break;
                    }
                }
            }
            if (stateField == null) throw new IllegalStateException("State alanı bulunamadı");
            Object mutableState = stateField.get(vm);
            invoke1(mutableState, "setValue", newState);
            Toast.makeText(this, "Sol / sağ değiştirildi", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Sol / sağ değiştirilemedi", Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean hasOneArgMethod(Class<?> c, String name) {
        for (Method m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == 1) return true;
        for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount() == 1) return true;
        return false;
    }

    private static Object invoke0(Object target, String name) throws Exception {
        Method m = target.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static void invoke1(Object target, String name, Object arg) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) {
                m.setAccessible(true);
                m.invoke(target, arg);
                return;
            }
        }
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) {
                m.setAccessible(true);
                m.invoke(target, arg);
                return;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Object constructByArity(Class<?> c, int arity, Object[] args) throws Exception {
        for (Constructor<?> ctor : c.getConstructors()) {
            if (ctor.getParameterCount() == arity) {
                ctor.setAccessible(true);
                return ctor.newInstance(args);
            }
        }
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == arity) {
                ctor.setAccessible(true);
                return ctor.newInstance(args);
            }
        }
        throw new NoSuchMethodException("constructor/" + arity + " for " + c.getName());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
