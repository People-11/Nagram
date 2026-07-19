package tw.nekomimi.nekogram.helpers.remote;

import org.json.JSONObject;

import java.util.ArrayList;

public class UpdateHelper extends BaseRemoteHelper {
    private static final class InstanceHolder {
        private static final UpdateHelper instance = new UpdateHelper();
    }

    public static UpdateHelper getInstance() {
        return InstanceHolder.instance;
    }

    @Override
    protected void onError(String text, Delegate delegate) {
        if (delegate != null) {
            delegate.onTLResponse(null, text);
        }
    }

    @Override
    protected String getTag() {
        return "update-disabled";
    }

    @Override
    protected void onLoadSuccess(ArrayList<JSONObject> responses, Delegate delegate) {
        if (delegate != null) {
            delegate.onTLResponse(null, null);
        }
    }

    @Override
    public void load() {
    }

    @Override
    public void load(Delegate delegate) {
        if (delegate != null) {
            delegate.onTLResponse(null, null);
        }
    }

    public void checkNewVersionAvailable(Delegate delegate) {
        checkNewVersionAvailable(delegate, false);
    }

    public void checkNewVersionAvailable(Delegate delegate, boolean updateAlways) {
        if (delegate != null) {
            delegate.onTLResponse(null, null);
        }
    }
}
