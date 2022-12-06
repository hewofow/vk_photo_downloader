package com.whfcd.vk_photo_downloader;

import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.UserActor;
import com.vk.api.sdk.exceptions.ApiException;
import com.vk.api.sdk.exceptions.ClientException;
import com.vk.api.sdk.httpclient.HttpTransportClient;
import com.vk.api.sdk.objects.photos.PhotoAlbumFull;
import com.vk.api.sdk.objects.photos.PhotoSizes;
import com.whfcd.vk_photo_downloader.models.PhotoUrl;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.stream.Collectors;

@Slf4j(topic = "logger")
public class VkPhotoDownloader {
    private static final int APP_ID;
    private static final String TOKEN;
    private static final int MAX_ITEMS_PER_REQUEST = 1000;

    private static final VkApiClient vk = new VkApiClient(new HttpTransportClient());
    private static final UserActor actor;

    static {
        int appId = 0;
        String token = "";

        try (Reader input = new FileReader("src/main/resources/application.properties")) {
            Properties prop = new Properties();
            prop.load(input);

            appId = Integer.parseInt(prop.getProperty("app.id"));
            token = prop.getProperty("app.token");
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        APP_ID = appId;
        TOKEN = token;
        actor = new UserActor(APP_ID, TOKEN);
    }

    public static void main(String[] args) throws ClientException, ApiException {
        System.out.println("Step 1. Please, TYPE IN the id of VK-user OR VK-community OWNING the desired albums: \n " +
                "  (NOTE: if it is community id - type it as a negative number, i. e. add the minus before the actual id!) ");
        Scanner scanner = new Scanner(System.in);
        int userId = scanner.hasNextInt() ? scanner.nextInt() :
                vk.users().get(actor).execute().get(0).getId();
        List<PhotoAlbumFull> allAlbums = getAllAlbumsInfo(userId);

        System.out.println("\nStep 2. Please, TYPE IN the ids of desired albums separated with whitespaces (see list above): \n" +
                "  (For example:  -6 126038923 -7)\n" +
                "  (NOTE: After pressing ENT press CMD+D also to finish typing)\n" +
                "  (NOTE: if you want to download ALL THE ALBUMS at once, do not provide anything (just terminate typing with CMD+D combination) )");
        scanner = new Scanner(System.in);
        List<Integer> desiredIds = new ArrayList<>();
        while (scanner.hasNextInt()) {
            desiredIds.add(scanner.nextInt());
        }

        log.info("Getting ready to download photos from the following albums belonging to owner with an id {}:", userId);
        List<PhotoAlbumFull> desiredAlbums = desiredIds.isEmpty() ? allAlbums :
                allAlbums.stream()
                    .filter(x -> desiredIds.stream().anyMatch(y -> y.equals(x.getId())))
                    .collect(Collectors.toList());
        desiredAlbums.forEach(x -> log.info("   {}, id: {}, size: {}", x.getTitle(), x.getId(), x.getSize()));

        downloadAllPhotos(getUrlsByAlbums(userId, desiredAlbums, true));
    }

    public static void downloadSinglePhoto(String destFolder, PhotoUrl photoUrl) {
        try {
            BufferedImage bufferedImage = ImageIO.read(new URL(photoUrl.getUrl()));
            Path path = Paths.get("photos", destFolder,
                    String.format("%s_%d.jpg", destFolder.replace(" ", "_").toLowerCase(), photoUrl.getId()));
            ImageIO.write(bufferedImage, "jpg", path.toFile());
            log.info("   successfully downloaded {}...", path);
        } catch (IOException e) {
            e.printStackTrace();
            log.error("   failed to download photo with index {} :(", photoUrl.getId());
        }
    }

    private static void downloadAllPhotos(Map<String, List<PhotoUrl>> photoUrlsByAlbumsMap) {
        log.info("Downloading photos...");
        for (Map.Entry<String, List<PhotoUrl>> entry : photoUrlsByAlbumsMap.entrySet()) {
            if (new File("photos/" + entry.getKey()).mkdirs()) {
                log.info("  folder \"{}\" created...", entry.getKey());
            }

            if (entry.getValue().size() > 15) {
                entry.getValue().parallelStream()
                        .forEach(photoUrl -> downloadSinglePhoto(entry.getKey(), photoUrl));
            } else {
                entry.getValue()
                        .forEach(photoUrl -> downloadSinglePhoto(entry.getKey(), photoUrl));
            }
        }
        log.info("Process completed!");
    }

    private static Map<String, List<PhotoUrl>> getUrlsByAlbums(int userId, List<PhotoAlbumFull> desiredAlbums, boolean saveLinksToFile) throws ApiException, ClientException {
        Map<String, List<PhotoUrl>> photosByAlbumsMap = getUrlsByAlbums(userId, desiredAlbums);

        if (saveLinksToFile) {
            saveUrlsToPhotosToFile(photosByAlbumsMap);
        }

        return photosByAlbumsMap;
    }

    private static Map<String, List<PhotoUrl>> getUrlsByAlbums(int userId, List<PhotoAlbumFull> desiredAlbums) throws ApiException, ClientException {
        log.info("Fetching links to photos...");
        int cycles;
        String albumIdAsString;
        Map<String, List<PhotoUrl>> photosByAlbumsMap = new HashMap<>();
        List<String> rawPhotosList;

        for (PhotoAlbumFull photoAlbum : desiredAlbums) {
            switch (photoAlbum.getId()) {
                case -6:
                    albumIdAsString = "profile";
                    break;
                case -7:
                    albumIdAsString = "wall";
                    break;
                case -15:
                    albumIdAsString = "saved";
                    break;
                case -9000:
                    albumIdAsString = "me";     // find out the proper tag!
                    break;
                default:
                    albumIdAsString = photoAlbum.getId().toString();
            }

            if (albumIdAsString.equals("me")) {
                continue;
            }

            cycles = (int) Math.ceil((float) photoAlbum.getSize() / MAX_ITEMS_PER_REQUEST);
            rawPhotosList = new ArrayList<>(photoAlbum.getSize());
            for (int i = 0; i < cycles; i++) {
                rawPhotosList.addAll(vk.photos().get(actor)
                        .ownerId(userId)
                        .offset(i * MAX_ITEMS_PER_REQUEST)
                        .count(MAX_ITEMS_PER_REQUEST)
                        .albumId(albumIdAsString)      // that causes a need to convert (see switch-case above) the numerical-id to its string form (for album of tagged photos string-id is unknown)
                        .execute()
                        .getItems().stream()
                        .map(photo -> photo.getSizes().stream()
                                .max(Comparator.comparing(PhotoSizes::getHeight))
                                .map(photoOfDesiredSize -> photoOfDesiredSize.getUrl().toString())
                                .orElse(""))
                        .filter(x -> !x.equals(""))
                        .collect(Collectors.toList()));
            }

            List<PhotoUrl> indexedPhotosList = new ArrayList<>(rawPhotosList.size());
            for (int i = 0; i < rawPhotosList.size(); i++) {
                indexedPhotosList.add(new PhotoUrl(i, rawPhotosList.get(i)));
            }

            photosByAlbumsMap.put(photoAlbum.getTitle(), indexedPhotosList);
            log.info("   successfully fetched {} links for album named \"{}\"", rawPhotosList.size(), photoAlbum.getTitle());
        }
        return photosByAlbumsMap;
    }

    private static List<PhotoAlbumFull> getAllAlbumsInfo(int userId) throws ApiException, ClientException {
        log.info("Getting info about all the albums belonging to the user with id {}...", userId);

        List<PhotoAlbumFull> albumsInfo = vk.photos().getAlbums(actor)
                .ownerId(userId)
//                .needCovers(true)
                .needSystem(true)
                .execute()
                .getItems();

        System.out.printf("List of all the albums of owner with id %d:\n", userId);
        albumsInfo.forEach(x -> System.out.printf("   - %s, id: %d, size: %d%n", x.getTitle(), x.getId(), x.getSize()));
        return albumsInfo;
    }

    private static void saveUrlsToPhotosToFile(Map<String, List<PhotoUrl>> photosByAlbumsMap) {
        log.info("Saving fetched links to file...");

        try (BufferedWriter bw = new BufferedWriter(new FileWriter("output.txt"))) {
            for (Map.Entry<String, List<PhotoUrl>> entry : photosByAlbumsMap.entrySet()) {
                try {
                    bw.write("\n\n\n\n\n\n" + entry.getKey() + "\n");
                    entry.getValue().forEach(photo ->
                            {
                                try {
                                    bw.write(photo.getUrl() + "\n");
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                    );
                    log.info("   {} photos links from album \"{}\" saved to output.txt file", entry.getValue().size(), entry.getKey());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
