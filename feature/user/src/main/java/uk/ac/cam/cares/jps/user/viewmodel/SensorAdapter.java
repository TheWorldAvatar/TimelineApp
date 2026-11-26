package uk.ac.cam.cares.jps.user.viewmodel;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;

import uk.ac.cam.cares.jps.user.R;
import uk.ac.cam.cares.jps.user.setting.sensor.SensorItem;

public class SensorAdapter extends RecyclerView.Adapter<SensorAdapter.SensorViewHolder> {
    private final SensorViewModel sensorViewModel;
    private final List<SensorItem> sensorItems;

    public SensorAdapter(List<SensorItem> sensorItems, SensorViewModel sensorViewModel) {
        this.sensorItems = sensorItems;
        this.sensorViewModel = sensorViewModel;
    }

    @NonNull
    @Override
    public SensorAdapter.SensorViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.sensor_item, parent, false);
        return new SensorViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SensorViewHolder holder, int position) {
        SensorItem sensorItem = sensorItems.get(position);
        holder.sensorText.setText(sensorItem.getSensorName());
        holder.sensorDescription.setText(sensorItem.getSensorDescription());

        holder.sensorToggleSwitch.setEnabled(true);
        holder.sensorToggleSwitch.setOnCheckedChangeListener(null);
        holder.sensorToggleSwitch.setChecked(sensorItem.isToggled());

        if (sensorItem.isToggleEnabled()) {
            holder.sensorToggleSwitch.setOnTouchListener(null);
            holder.sensorToggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                sensorItem.setToggled(isChecked);
                sensorViewModel.toggleSensor(sensorItem.getSensorType());
            });
        } else {
            holder.sensorToggleSwitch.setOnTouchListener((v, event) -> true);
        }
    }

    @Override
    public int getItemCount() {
        return sensorItems.size();
    }

    public List<SensorItem> getSensorItems() {
        return sensorItems;
    }

    static class SensorViewHolder extends RecyclerView.ViewHolder {
        TextView sensorText;
        MaterialSwitch sensorToggleSwitch;
        TextView sensorDescription;

        public SensorViewHolder(@NonNull View itemView) {
            super(itemView);
            sensorText = itemView.findViewById(R.id.sensor_name);
            sensorToggleSwitch = itemView.findViewById(R.id.sensor_toggle);
            sensorDescription = itemView.findViewById(R.id.sensor_description);
        }
    }

    public void setTogglesEnabled(boolean enabled) {
        for (SensorItem sensorItem : sensorItems) {
            sensorItem.setToggleEnabled(enabled);
        }
        notifyDataSetChanged();
    }

    public void updateSensorItems(List<SensorItem> newSensorItems) {
        this.sensorItems.clear();
        this.sensorItems.addAll(newSensorItems);
    }
}