package eu.wohlben.qits.projects.mapper;

import eu.wohlben.qits.projects.control.ProjectConfig.DeliverySettings;
import eu.wohlben.qits.projects.dto.ProjectDeliveryDto;
import eu.wohlben.qits.projects.dto.ProjectDnsRecordDto;
import eu.wohlben.qits.projects.dto.ProjectDto;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ProjectDnsRecord;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface ProjectMapper {

  @Mapping(target = "delivery", expression = "java(deliveryOf(entity))")
  ProjectDto toDto(Project entity);

  /** The four {@code delivery_*} columns as one object (epic qits-1440); approves sorted. */
  default ProjectDeliveryDto deliveryOf(Project entity) {
    DeliverySettings delivery = DeliverySettings.of(entity);
    return new ProjectDeliveryDto(
        delivery.enabled(),
        delivery.maxRunning(),
        delivery.startRefined(),
        delivery.approves().stream().sorted().toList());
  }

  /**
   * Declared explicitly so the generated nested mapping keeps <b>null in, null out</b>: MapStruct's
   * default for a nested bean is to map a null source to a null target, which is what makes a
   * project with no domain serialize as {@code "dns": null} rather than as an object of three
   * nulls. Hibernate hands us the null in the first place — an {@code @Embedded} whose every column
   * is null is read as a null field — and this is the other half of keeping "no domain" one state
   * instead of two.
   */
  ProjectDnsRecordDto toDto(ProjectDnsRecord entity);
}
