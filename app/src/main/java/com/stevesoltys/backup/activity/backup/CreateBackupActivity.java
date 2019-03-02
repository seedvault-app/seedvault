package com.stevesoltys.backup.activity.backup;

import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import com.stevesoltys.backup.R;
import com.stevesoltys.backup.activity.PackageListActivity;

import java.util.HashSet;

public class CreateBackupActivity extends PackageListActivity implements View.OnClickListener {

    private CreateBackupActivityController controller;

    private Uri contentUri;

    @Override
    public void onClick(View view) {
        int viewId = view.getId();

        if (viewId == R.id.create_confirm_button) {
            controller.showEnterPasswordAlert(selectedPackageList, contentUri, this);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_create_backup);
        findViewById(R.id.create_confirm_button).setOnClickListener(this);

        packageListView = findViewById(R.id.create_package_list);
        selectedPackageList = new HashSet<>();
        contentUri = getIntent().getData();

        controller = new CreateBackupActivityController();
        AsyncTask.execute(() -> controller.populatePackageList(packageListView, CreateBackupActivity.this));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.backup_menu, menu);
        return true;
    }
}
