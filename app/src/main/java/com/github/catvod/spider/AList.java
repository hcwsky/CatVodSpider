package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.alist.Drive;
import com.github.catvod.bean.alist.Item;
import com.github.catvod.bean.alist.Sorter;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttpUtil;
import com.github.catvod.utils.Misc;
import com.github.catvod.utils.Trans;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class AList extends Spider {

    private List<Drive> drives;
    private String ext;

    private List<Filter> getFilter() {
        List<Filter> items = new ArrayList<>();
        items.add(new Filter("type", "排序類型", Arrays.asList(new Filter.Value("名稱", "name"), new Filter.Value("大小", "size"), new Filter.Value("修改時間", "date"))));
        items.add(new Filter("order", "排序方式", Arrays.asList(new Filter.Value("⬆", "asc"), new Filter.Value("⬇", "desc"))));
        return items;
    }

    private void fetchRule() {
        if (drives != null && !drives.isEmpty()) return;
        if (ext.startsWith("http")) ext = OkHttpUtil.string(ext);
        drives = Drive.objectFrom(ext).getDrives();
    }

    private Drive getDrive(String name) {
        Drive drive = drives.get(drives.indexOf(new Drive(name)));
        if (drive.getVersion() == 0) drive.setVersion(OkHttpUtil.string(drive.settingsApi()).contains("v3.") ? 3 : 2);
        return drive;
    }

    @Override
    public void init(Context context, String extend) {
        try {
            ext = extend;
            fetchRule();
        } catch (Exception ignored) {
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        fetchRule();
        List<Class> classes = new ArrayList<>();
        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        for (Drive drive : drives) classes.add(drive.toType());
        for (Class item : classes) filters.put(item.getTypeId(), getFilter());
        return Result.string(classes, filters);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        fetchRule();
        String type = extend.containsKey("type") ? extend.get("type") : "name";
        String order = extend.containsKey("order") ? extend.get("order") : "asc";
        List<Item> folders = new ArrayList<>();
        List<Item> files = new ArrayList<>();
        List<Vod> list = new ArrayList<>();
        for (Item item : getList(tid, true)) {
            if (item.isFolder()) folders.add(item);
            else files.add(item);
        }
        Sorter.sort(type, order, folders);
        Sorter.sort(type, order, files);
        for (Item item : folders) list.add(item.getVod(tid));
        for (Item item : files) list.add(item.getVod(tid));
        return Result.get().vod(list).page().string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        fetchRule();
        String id = ids.get(0);
        Item item = getDetail(id);
        String path = id.substring(0, id.lastIndexOf("/"));
        List<Item> parents = getList(path, false);
        Vod vod = new Vod();
        vod.setVodId(item.getVodId(id));
        vod.setVodName(item.getName());
        vod.setVodPic(item.getPic());
        vod.setVodTag(item.getVodTag());
        vod.setVodPlayFrom("播放");
        vod.setVodPlayUrl(Trans.get(item.getName()) + "$" + item.getUrl() + findSubs(path, parents));
        return Result.string(vod);
    }

    private Item getDetail(String id) {
        try {
            String key = id.contains("/") ? id.substring(0, id.indexOf("/")) : id;
            String path = id.contains("/") ? id.substring(id.indexOf("/") + 1) : "";
            Drive drive = getDrive(key);
            JSONObject params = new JSONObject();
            params.put("path", path);
            params.put("password", drive.getPassword());
            String response = OkHttpUtil.postJson(drive.getApi(), params.toString());
            String json = drive.isNew() ? new JSONObject(response).getJSONObject("data").toString() : new JSONObject(response).getJSONObject("data").getJSONArray("files").getJSONObject(0).toString();
            return Item.objectFrom(json);
        } catch (Exception e) {
            return new Item();
        }
    }

    private List<Item> getList(String id, boolean filter) {
        try {
            String key = id.contains("/") ? id.substring(0, id.indexOf("/")) : id;
            String path = id.contains("/") ? id.substring(id.indexOf("/") + 1) : "";
            Drive drive = getDrive(key);
            JSONObject params = new JSONObject();
            params.put("path", path);
            params.put("password", drive.getPassword());
            String response = OkHttpUtil.postJson(drive.listApi(), params.toString());
            String json = new JSONObject(response).getJSONObject("data").getJSONArray(drive.isNew() ? "content" : "files").toString();
            List<Item> items = Item.arrayFrom(json);
            Iterator<Item> iterator = items.iterator();
            if (filter) while (iterator.hasNext()) if (iterator.next().ignore(drive.isNew())) iterator.remove();
            return items;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public String searchContent(String keyword, boolean quick) throws Exception {
        fetchRule();
        List<Vod> list = new ArrayList<>();
        CountDownLatch cd = new CountDownLatch(drives.size());
        for (Drive drive : drives) new Thread(() -> search(cd, list, drive, keyword)).start();
        cd.await();
        return Result.string(list);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String[] ids = id.split("~~~");
        return Result.get().url(ids[0]).sub(getSub(ids)).string();
    }

    private String getParams(String keyword) {
        try {
            JSONObject params = new JSONObject();
            params.put("path", "/");
            params.put("keyword", keyword);
            return params.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void search(CountDownLatch cd, List<Vod> list, Drive drive, String keyword) {
        if (drive.isNew()) searchV3(list, drive, keyword);
        else searchV2(list, drive, getParams(keyword));
        cd.countDown();
    }

    private void searchV3(List<Vod> list, Drive drive, String param) {
        Document doc = Jsoup.parse(OkHttpUtil.string(drive.searchApi(param)));
        for (Element a : doc.select("ul > a")) {
            String text = a.text();
            String[] splits = text.split("\\.");
            boolean file = splits.length > 1 && splits[1].length() == 3;
            Item item = new Item();
            int index = text.lastIndexOf("/");
            if (index == -1) continue;
            item.setPath("/" + text.substring(0, index));
            item.setName(text.substring(index + 1));
            item.setType(file ? 0 : 1);
            list.add(item.getVod(drive.getName()));
        }
    }

    private void searchV2(List<Vod> list, Drive drive, String param) {
        try {
            String response = OkHttpUtil.postJson(drive.searchApi(), param);
            String json = new JSONObject(response).getJSONArray("data").toString();
            List<Item> items = Item.arrayFrom(json);
            for (Item item : items) if (!item.ignore(false)) list.add(item.getVod(drive.getName()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String findSubs(String path, List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item item : items) if (Misc.isSub(item.getExt())) sb.append("~~~").append(Trans.get(item.getName())).append("@").append(Misc.getSubMimeType(item.getExt())).append("@").append(item.getVodId(path));
        return sb.toString();
    }

    private String getSub(String[] ids) {
        StringBuilder sb = new StringBuilder();
        for (String text : ids) {
            if (!text.contains("@")) continue;
            String[] arr = text.split("@");
            sb.append(arr[0]).append("#").append(arr[1]).append("#").append(getDetail(arr[2]).getUrl()).append("$$$");
        }
        return Misc.substring(sb.toString(), 3);
    }
}