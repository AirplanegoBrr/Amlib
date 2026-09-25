package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftDeviceCodeLogin;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;

/** Microsoft sign-in by entering a code on another device, instead of the web login */
public class MicrosoftDeviceLoginFragment extends Fragment {
    public static final String TAG = "MICROSOFT_DEVICE_LOGIN_FRAGMENT";
    private volatile boolean mCancelled;

    public MicrosoftDeviceLoginFragment() {
        super(R.layout.fragment_microsoft_device_login);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        TextView instructions = view.findViewById(R.id.device_login_instructions);
        TextView code = view.findViewById(R.id.device_login_code);
        Button openLink = view.findViewById(R.id.device_login_open_link);
        mCancelled = false;

        sExecutorService.execute(() -> {
            try {
                MicrosoftDeviceCodeLogin.DeviceCode deviceCode = MicrosoftDeviceCodeLogin.requestCode();
                Tools.runOnUiThread(() -> {
                    if (mCancelled) return;
                    instructions.setText(getString(R.string.device_login_instructions, deviceCode.verificationUri));
                    code.setText(deviceCode.userCode);
                    openLink.setVisibility(View.VISIBLE);
                    openLink.setOnClickListener(v -> Tools.openURL(requireActivity(), deviceCode.verificationUri));
                });

                String refreshToken = MicrosoftDeviceCodeLogin.waitForRefreshToken(deviceCode, () -> mCancelled);
                if (refreshToken == null) return;
                Tools.runOnUiThread(() -> {
                    // Handled by mcAccountSpinner, which finishes the Xbox/Minecraft part of the login
                    ExtraCore.setValue(ExtraConstants.MICROSOFT_DEVICE_LOGIN_TODO, refreshToken);
                    if (mCancelled) return;
                    Toast.makeText(requireContext(), R.string.device_login_started, Toast.LENGTH_SHORT).show();
                    Tools.backToMainMenu(requireActivity());
                });
            } catch (Exception e) {
                Log.e(TAG, "Device code sign-in failed", e);
                Tools.runOnUiThread(() -> {
                    if (mCancelled) return;
                    instructions.setText(getString(R.string.device_login_failed, e.getMessage()));
                    code.setText("");
                    openLink.setVisibility(View.GONE);
                });
            }
        });
    }

    @Override
    public void onDestroyView() {
        mCancelled = true;
        super.onDestroyView();
    }
}
