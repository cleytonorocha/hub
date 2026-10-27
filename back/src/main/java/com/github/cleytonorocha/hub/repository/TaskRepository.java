package com.github.cleytonorocha.hub.repository;

import com.github.cleytonorocha.hub.entity.Task;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, String> {}
