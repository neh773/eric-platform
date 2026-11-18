//! CPU list written by the resource split. The scan uses it when `plc.toml` does not name a core.

use serde_json::Value;

pub fn resources_path() -> String {
    if let Ok(path) = std::env::var("ERIC_PLC_RESOURCES") {
        if !path.is_empty() {
            return path;
        }
    }
    #[cfg(windows)]
    {
        return "C:\\ProgramData\\ERIC\\plc-resources.json".into();
    }
    #[cfg(not(windows))]
    {
        "/etc/eric/plc-resources.json".into()
    }
}

pub fn assigned_cpus() -> Vec<usize> {
    match std::fs::read_to_string(resources_path()) {
        Ok(text) => parse_plc_cpus(&text).unwrap_or_default(),
        Err(_) => Vec::new(),
    }
}

pub fn assigned_memory_bytes() -> u64 {
    let Ok(text) = std::fs::read_to_string(resources_path()) else { return 0 };
    let Ok(value) = serde_json::from_str::<Value>(&text) else { return 0 };
    value.get("plcMemoryBytes").and_then(Value::as_u64).unwrap_or(0)
}

pub fn parse_plc_cpus(text: &str) -> Result<Vec<usize>, String> {
    let value: Value = serde_json::from_str(text).map_err(|err| err.to_string())?;
    let list = value.get("plcCpus").and_then(Value::as_array).ok_or("plcCpus is missing")?;
    let mut cpus = Vec::new();
    for item in list {
        let id = item.as_u64().ok_or("plcCpus entries must be numbers")? as usize;
        cpus.push(id);
    }
    Ok(cpus)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_resource_file_names_the_plc_cpus() {
        let text = r#"{"generator":"eric-plc-resources","plcCpus":[2,6],"isolatedCpus":[2,3,6,7]}"#;
        assert_eq!(parse_plc_cpus(text).unwrap(), vec![2, 6]);
    }
}
