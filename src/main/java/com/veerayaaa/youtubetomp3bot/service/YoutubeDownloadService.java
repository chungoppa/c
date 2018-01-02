package com.veerayaaa.youtubetomp3bot.service;

import com.veerayaaa.youtubetomp3bot.YouTubeToMp3BotApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Created by pook on 6/11/2017.
 */
@Service
public class YoutubeDownloadService {
    private static final Logger logger = LoggerFactory.getLogger(YoutubeDownloadService.class);

    AmazonS3Service amazonS3Service;

    public static void main(String... args) throws IOException, InterruptedException {
        AmazonS3Service amazonS3Service = new AmazonS3Service();
        YoutubeDownloadService s = new YoutubeDownloadService(amazonS3Service);
        s.getLinkFromVideo("https://youtu.be/1guOQX6_UPo");
    }

    @Autowired
    public YoutubeDownloadService(AmazonS3Service amazonS3Service) {
        this.amazonS3Service = amazonS3Service;
    }

    public String getLinkFromVideo(String youtubeLink) throws IOException, InterruptedException {
        String mp3FileName = getMp3FileName(youtubeLink);
        logger.info("MP3 file name: " + mp3FileName);
        String downloadedContentDir = YouTubeToMp3BotApp.downloadedContentDir != null ? YouTubeToMp3BotApp.downloadedContentDir.toAbsolutePath().toString() : Files.createTempDirectory("downloadedVideo").toAbsolutePath().toString();
        String command = getBaseYoutubeDlCommand(youtubeLink, downloadedContentDir);
        logger.info("Running process: [{}]", command);
        final Process p = Runtime.getRuntime().exec(command);
        BufferedReader input = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line = null;
        try {
            while ((line = input.readLine()) != null) {
                logger.info("FFMPEG: {}", line);
            }
        } catch (IOException e) {
            logger.error("Error: {}", e);
        }
        printError(p);
        p.waitFor();
        String mp3Link = uploadFileToS3(downloadedContentDir + "/" + mp3FileName, mp3FileName);
        return mp3Link;
    }

    private void printError(Process p) {
        BufferedReader input = new BufferedReader(new InputStreamReader(p.getErrorStream()));
        String line = null;
        try {
            while ((line = input.readLine()) != null) {
                logger.info("ERROR: {}", line);
            }
        } catch (IOException e) {
            logger.error("Error: {}", e);
        }
    }

    private String getMp3FileName(String youtubeLink) throws IOException, InterruptedException {
        String command = getYoutubeDlFileNameCommand(youtubeLink);
        logger.info("Running process: [{}]", command);
        final Process p = Runtime.getRuntime().exec(command);
        p.waitFor();  // wait for process to finish then continue.

        BufferedReader bri = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String videoFileName = bri.readLine();
        /* Ugly hack to prevent people from downloading large file.
        * TODO: revisit the logic and remove hardcoding
        * */
        String duration = bri.readLine();

        if (duration.split(":").length > 2 || Integer.parseInt(duration.split(":")[0]) > 10) {
            throw new RuntimeException("Error: Please download a video shorter than 10 minutes");
        }
        String[] nameParts = videoFileName.split("\\.");
        return String.join(".", Arrays.copyOfRange(nameParts, 0, nameParts.length - 1)) + ".mp3";
    }

    private String getYoutubeDlFileNameCommand(String youtubeLink) {
        return getBaseYoutubeDlCommand(youtubeLink, "") + " --get-filename --get-duration";
    }

    private String getBaseYoutubeDlCommand(String youtubeLink, String downloadPath) {
        String outputFormat = "%(title)s.%(ext)s";
        String command = "youtube-dl " + youtubeLink + " -f bestaudio --extract-audio --audio-format mp3 --audio-quality 0 ";
        if (StringUtils.isEmpty(downloadPath)) {
            command += "-o " + outputFormat;
        } else {
            command += "-o " + downloadPath + "/" + outputFormat;
        }
        return command;
    }

    private String uploadFileToS3(String localPath, String filename) {
        InputStream inputSteam = null;
        try {
            inputSteam = new FileInputStream(localPath);
            return amazonS3Service.uploadMp3(filename, inputSteam);
        } catch (Exception e) {
            logger.error("Error occurred while downloading file or upload file", e);
            throw new RuntimeException(e);
        } finally {
            try {
                inputSteam.close();
            } catch (IOException e) {
                logger.debug("Fali to close stream?: ", e);
            }
        }
    }
}
