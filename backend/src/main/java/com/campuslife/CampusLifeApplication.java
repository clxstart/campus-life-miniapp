package com.campuslife;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication
@EnableScheduling
@MapperScan("com.campuslife.mapper")
public class CampusLifeApplication { public static void main(String[] args){ SpringApplication.run(CampusLifeApplication.class,args); } }
