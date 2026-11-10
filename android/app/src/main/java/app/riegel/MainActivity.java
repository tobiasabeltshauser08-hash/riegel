package app.riegel;

import android.os.Bundle;

import app.riegel.guard.GuardPlugin;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(GuardPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
