package com.stardust.scriptdroid.external.open;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.widget.Toast;

import com.stardust.scriptdroid.autojs.AutoJs;
import com.stardust.scriptdroid.script.PathChecker;
import com.stardust.autojs.script.FileScriptSource;
import com.stardust.autojs.script.ScriptSourceWithInit;
import com.stardust.autojs.script.ScriptSource;
import com.stardust.autojs.script.StringScriptSource;
import com.stardust.scriptdroid.external.CommonUtils;
import com.stardust.scriptdroid.R;
import com.stardust.scriptdroid.script.Scripts;

/**
 * Created by Stardust on 2017/2/22.
 */

public class RunIntentActivity extends Activity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            handleIntent(getIntent());
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, R.string.edit_and_run_handle_intent_error, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private void handleIntent(Intent intent) {
        String path = getPath(intent);
        String script = intent.getStringExtra(CommonUtils.EXTRA_KEY_PRE_EXECUTE_SCRIPT);
        ScriptSource source = null;
        if (path == null && script != null) {
            source = new StringScriptSource(script);
        } else if (path != null && new PathChecker(this).checkAndToastError(path)) {
            source = new ScriptSourceWithInit(new StringScriptSource(script), new FileScriptSource(path));
        }
        if (source != null) {
            Scripts.run(source);
        }
    }

    private String getPath(Intent intent) {
        if (intent.getData() != null && intent.getData().getPath() != null)
            return intent.getData().getPath();
        return intent.getStringExtra(CommonUtils.EXTRA_KEY_PATH);
    }
}
