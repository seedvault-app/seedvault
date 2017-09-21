package com.stevesoltys.backup.activity.backup;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;

import com.stevesoltys.backup.R;

import java.util.LinkedList;
import java.util.List;

public class CreateBackupActivity extends Activity implements View.OnClickListener, AdapterView.OnItemClickListener {

    private CreateBackupActivityController controller;

    private ListView packageListView;

    private List<String> selectedPackageList;

    private Uri contentUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_create_backup);
        findViewById(R.id.create_confirm_button).setOnClickListener(this);

        packageListView = findViewById(R.id.create_package_list);
        selectedPackageList = new LinkedList<>();
        contentUri = getIntent().getData();

        controller = new CreateBackupActivityController();
        controller.populatePackageList(packageListView, this);
    }

    @Override
    public void onClick(View view) {
        int viewId = view.getId();

        switch (viewId) {

            case R.id.create_confirm_button:
                controller.backupPackages(selectedPackageList, contentUri, this);
                break;
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        String clickedPackage = (String) packageListView.getItemAtPosition(position);

        if (!selectedPackageList.remove(clickedPackage)) {
            selectedPackageList.add(clickedPackage);
            packageListView.setItemChecked(position, true);

        } else {
            packageListView.setItemChecked(position, false);
        }
    }
}
