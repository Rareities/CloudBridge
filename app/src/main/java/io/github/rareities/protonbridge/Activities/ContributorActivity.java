package io.github.rareities.protonbridge.Activities;

import android.os.Bundle;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowCompat;

import io.github.rareities.protonbridge.util.ActivityHelper;
import io.github.rareities.protonbridge.R;
import io.github.rareities.protonbridge.util.MarkdownView;


public class ContributorActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        ActivityHelper.applyTheme(this);
        try {
            setContentView(R.layout.activity_contributors);
        } catch (Exception e) {
            MarkdownView.closeOnMissingWebView(this, e);
        }
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
        }

        MarkdownView markdownView = findViewById(R.id.markdownView);
        markdownView.loadAsset("contributors.md");
    }

    @Override
    protected void onDestroy() {
        MarkdownView view = findViewById(R.id.markdownView);
        if (view != null) view.destroy();
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}
