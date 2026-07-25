package eu.wohlben.qits.epics.mapper;

import eu.wohlben.qits.epics.dto.FeatureDto;
import eu.wohlben.qits.epics.entity.Feature;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface FeatureMapper {
  FeatureDto toDto(Feature entity);
}
