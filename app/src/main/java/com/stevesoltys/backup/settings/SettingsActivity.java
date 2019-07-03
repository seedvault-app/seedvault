package com.stevesoltys.backup.settings;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProviders;

import com.stevesoltys.backup.R;

import static android.content.Intent.ACTION_OPEN_DOCUMENT_TREE;
import static android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
import static android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
import static android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static com.stevesoltys.backup.activity.MainActivity.OPEN_DOCUMENT_TREE_REQUEST_CODE;
import static java.util.Objects.requireNonNull;

public class SettingsActivity extends AppCompatActivity {

    private final static String TAG = SettingsActivity.class.getName();

    private SettingsViewModel viewModel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_settings);

        viewModel = ViewModelProviders.of(this).get(SettingsViewModel.class);

        requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!viewModel.locationIsSet()) {
            showChooseFolderActivity();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.settings_menu, menu);
        if (getResources().getBoolean(R.bool.show_restore_in_settings)) {
            menu.findItem(R.id.action_restore).setVisible(true);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (item.getItemId() == R.id.action_backup) {
            Toast.makeText(this, "Not yet implemented", LENGTH_SHORT).show();
            return true;
        } else if (item.getItemId() == R.id.action_restore) {
            Toast.makeText(this, "Not yet implemented", LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent result) {
        if (resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "Error in activity result: " + requestCode);
            return;
        }

        if (requestCode == OPEN_DOCUMENT_TREE_REQUEST_CODE) {
            viewModel.handleChooseFolderResult(result);
        }
    }

    private void showChooseFolderActivity() {
        Intent openTreeIntent = new Intent(ACTION_OPEN_DOCUMENT_TREE);
        openTreeIntent.addFlags(FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            Intent documentChooser = Intent.createChooser(openTreeIntent, "Select the backup location");
            startActivityForResult(documentChooser, OPEN_DOCUMENT_TREE_REQUEST_CODE);
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(this, "Please install a file manager.", LENGTH_LONG).show();
        }
    }

}
