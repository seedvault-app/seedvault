package com.stevesoltys.backup.activity.restore;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import com.stevesoltys.backup.R;

import java.util.HashSet;
import java.util.Set;

public class RestoreBackupActivity extends Activity implements View.OnClickListener, AdapterView.OnItemClickListener {

    private RestoreBackupActivityController controller;

    private ListView packageListView;

    private Set<String> selectedPackageList;

    private Uri contentUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_restore_backup);
        findViewById(R.id.restore_confirm_button).setOnClickListener(this);

        packageListView = findViewById(R.id.restore_package_list);
        selectedPackageList = new HashSet<>();
        contentUri = getIntent().getData();

        controller = new RestoreBackupActivityController();
        controller.populatePackageList(packageListView, contentUri, this);
    }

    @Override
    public void onClick(View view) {
        int viewId = view.getId();

        switch (viewId) {

            case R.id.restore_confirm_button:
                controller.restorePackages(selectedPackageList, contentUri, this);
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
